package vn.ledat.platform.core.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.gui.GuiButton;
import vn.ledat.platform.api.gui.GuiClick;
import vn.ledat.platform.api.gui.GuiClose;
import vn.ledat.platform.api.gui.GuiService;
import vn.ledat.platform.api.gui.GuiSession;
import vn.ledat.platform.api.gui.GuiItemDefinition;
import vn.ledat.platform.api.profile.GameProfileService;
import vn.ledat.platform.api.language.LanguageService;
import vn.ledat.platform.api.scheduler.LeDatScheduler;
import vn.ledat.platform.api.scheduler.LeDatTask;
import vn.ledat.platform.api.sound.SoundCue;
import vn.ledat.platform.api.sound.SoundService;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.concurrent.CompletableFuture;

public final class DefaultGuiService implements GuiService, Listener {
    private static final String LITERAL_TITLE_PREFIX = "literal:";
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final Plugin platformPlugin;
    private final LanguageService languages;
    private final LeDatScheduler scheduler;
    private final SoundService sounds;
    private final Map<UUID, GuiSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> skipCloseCleanup = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastClickAt = new ConcurrentHashMap<>();
    private final AtomicLong blockedRapidClicks = new AtomicLong();
    private final AtomicLong expiredSessionCleanups = new AtomicLong();
    private final AtomicLong failedOpens = new AtomicLong();
    private final LeDatTask cleanupTask;
    private final GuiItemRenderer itemRenderer;

    public DefaultGuiService(Plugin platformPlugin, LanguageService languages, LeDatScheduler scheduler, SoundService sounds) {
        this(platformPlugin, languages, scheduler, sounds, GameProfileService.disabled());
    }

    public DefaultGuiService(Plugin platformPlugin, LanguageService languages, LeDatScheduler scheduler, SoundService sounds,
                             GameProfileService profiles) {
        this.platformPlugin = platformPlugin;
        this.languages = languages;
        this.scheduler = scheduler;
        this.sounds = sounds == null ? SoundService.disabled() : sounds;
        this.itemRenderer = new GuiItemRenderer(profiles == null ? GameProfileService.disabled() : profiles, scheduler);
        Bukkit.getPluginManager().registerEvents(this, platformPlugin);
        long intervalSeconds = Math.max(10L, platformPlugin.getConfig().getLong("gui.stale-cleanup-interval-seconds", 30L));
        this.cleanupTask = scheduler.repeat(platformPlugin, this::cleanupExpiredSessions, intervalSeconds * 20L, intervalSeconds * 20L);
    }

    @Override
    public CompletableFuture<ItemStack> renderItem(Plugin owner, Player viewer, GuiItemDefinition definition,
                                                   Map<String, String> placeholders) {
        return itemRenderer.render(owner == null ? platformPlugin : owner, viewer, definition, placeholders);
    }

    @Override
    public PlatformGui.Builder gui(Plugin plugin, String menuId) {
        return new SimplePlatformGui.Builder(plugin == null ? platformPlugin : plugin, menuId == null || menuId.isBlank() ? "default" : menuId);
    }

    @Override
    public void open(Player player, PlatformGui gui) {
        if (player == null || gui == null) {
            return;
        }
        runForPlayer(player, () -> openDirect(player, gui));
    }

    private void openDirect(Player player, PlatformGui gui) {
        if (!gui.plugin().isEnabled()) {
            return;
        }
        GuiSession session = new GuiSession(player.getUniqueId(), gui.plugin().getName(), gui.menuId(), gui.page());
        gui.context().forEach(session::put);
        int inventorySize = normalizeSize(gui.size());
        Component title = resolveTitle(player, gui, inventorySize);
        if (inventorySize != gui.size()) {
            gui.plugin().getLogger().warning("Invalid platform GUI size " + gui.size() + " for " + gui.menuId() + "; normalized to " + inventorySize);
        }
        PlatformGuiHolder holder = new PlatformGuiHolder(gui, session);
        Inventory inventory;
        try {
            inventory = Bukkit.createInventory(holder, inventorySize, title);
        } catch (Throwable error) {
            failedOpens.incrementAndGet();
            gui.plugin().getLogger().log(Level.WARNING, "Could not create platform GUI " + gui.menuId() + " size=" + inventorySize, error);
            languages.send(player, gui.plugin(), "gui.error", Map.of("menu", gui.menuId()));
            return;
        }
        holder.inventory(inventory);
        if (gui instanceof SimplePlatformGui simple && simple.fill() != null) {
            ItemStack fill = simple.fill();
            for (int i = 0; i < inventorySize; i++) {
                inventory.setItem(i, GuiService.nonItalicItem(fill));
            }
        }
        for (Map.Entry<Integer, ItemStack> entry : gui.staticItems().entrySet()) {
            int slot = entry.getKey();
            ItemStack item = entry.getValue();
            if (slot >= 0 && slot < inventorySize && item != null) {
                inventory.setItem(slot, GuiService.nonItalicItem(item));
            }
        }
        for (Map.Entry<Integer, GuiButton> entry : gui.buttons().entrySet()) {
            int slot = entry.getKey();
            GuiButton button = entry.getValue();
            if (slot >= 0 && slot < inventorySize && button != null && button.item() != null) {
                inventory.setItem(slot, GuiService.nonItalicItem(button.item()));
            }
        }

        // Opening a new platform GUI closes the previous one. Mark that close as a
        // controlled reopen so the previous close event cannot wipe the new session.
        Inventory previousInventory = null;
        try {
            previousInventory = player.getOpenInventory().getTopInventory();
            if (previousInventory.getHolder() instanceof PlatformGuiHolder) {
                skipCloseCleanup.add(player.getUniqueId());
            }
        } catch (Throwable ignored) {
        }

        GuiSession previousSession = sessions.put(player.getUniqueId(), session);
        try {
            var opened = player.openInventory(inventory);
            if (opened == null || opened.getTopInventory() != inventory
                    || player.getOpenInventory().getTopInventory() != inventory) {
                failedOpens.incrementAndGet();
                discardFailedOpen(player, session, previousSession, previousInventory);
            }
        } catch (Throwable error) {
            failedOpens.incrementAndGet();
            discardFailedOpen(player, session, previousSession, previousInventory);
            gui.plugin().getLogger().log(Level.WARNING, "Could not open platform GUI " + gui.menuId() + " for " + player.getName(), error);
            languages.send(player, gui.plugin(), "gui.error", Map.of("menu", gui.menuId()));
        }
    }

    private void discardFailedOpen(Player player, GuiSession attempted, GuiSession previous, Inventory previousInventory) {
        UUID playerId = player.getUniqueId();
        if (!sessions.remove(playerId, attempted)) {
            return;
        }
        skipCloseCleanup.remove(playerId);
        // A cancelled open may leave the previous window visible. Restore only
        // that exact still-visible session; nested successful opens own their map entry.
        if (previous == null || previousInventory == null) {
            return;
        }
        try {
            if (player.getOpenInventory().getTopInventory() == previousInventory
                    && previousInventory.getHolder() instanceof PlatformGuiHolder holder
                    && holder.session().sessionId().equals(previous.sessionId())) {
                sessions.putIfAbsent(playerId, previous);
            }
        } catch (Throwable ignored) {
            // Session removal remains valid when the player no longer has a usable view.
        }
    }

    private Component resolveTitle(Player player, PlatformGui gui, int inventorySize) {
        if (gui.title() != null) {
            return InventoryTitleNormalizer.normalize(gui.title(), inventorySize);
        }
        String titleKey = gui.titleKey();
        if (titleKey != null && titleKey.startsWith(LITERAL_TITLE_PREFIX)) {
            String raw = InventoryTitleNormalizer.normalize(titleKey.substring(LITERAL_TITLE_PREFIX.length()), inventorySize);
            try {
                return MINI.deserialize(raw);
            } catch (Exception ignored) {
                return Component.text(raw);
            }
        }
        try {
            return InventoryTitleNormalizer.normalize(languages.component(gui.plugin(), languages.resolveLocale(player), titleKey,
                    Map.of("page", String.valueOf(gui.page() + 1), "rows", String.valueOf(inventorySize / 9))), inventorySize);
        } catch (Throwable error) {
            gui.plugin().getLogger().log(Level.WARNING, "Could not resolve GUI title " + titleKey + " for " + gui.menuId(), error);
            return Component.text(gui.menuId());
        }
    }

    @Override
    public void close(Player player, boolean clearSession) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (clearSession) {
            sessions.remove(uuid);
        } else {
            skipCloseCleanup.add(uuid);
        }
        runForPlayer(player, player::closeInventory);
    }

    @Override
    public void closeSession(UUID playerId, UUID expectedSessionId) {
        if (playerId == null || expectedSessionId == null || !currentSession(playerId, expectedSessionId)) {
            return;
        }
        scheduler.sync(platformPlugin, () -> {
            if (!currentSession(playerId, expectedSessionId)) {
                return;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                removeSession(playerId, expectedSessionId);
                return;
            }
            scheduler.entityOperation(platformPlugin, player, () -> {
                if (!removeSession(playerId, expectedSessionId)) {
                    return false;
                }
                player.closeInventory();
                return true;
            }).whenComplete((outcome, error) -> {
                if (error != null || outcome == null || !outcome.executed()) {
                    // Map-only cleanup is safe outside the retired entity owner and
                    // uses compare/remove so it cannot delete a replacement GUI.
                    removeSession(playerId, expectedSessionId);
                }
            });
        });
    }

    @Override
    public GuiSession session(UUID playerId) {
        return playerId == null ? null : sessions.get(playerId);
    }

    private boolean currentSession(UUID playerId, UUID expectedSessionId) {
        GuiSession current = sessions.get(playerId);
        return current != null && expectedSessionId.equals(current.sessionId());
    }

    private boolean removeSession(UUID playerId, UUID expectedSessionId) {
        GuiSession current = sessions.get(playerId);
        if (current == null || !expectedSessionId.equals(current.sessionId())
                || !sessions.remove(playerId, current)) {
            return false;
        }
        skipCloseCleanup.remove(playerId);
        lastClickAt.remove(playerId);
        return true;
    }

    @Override
    public void cleanup(UUID playerId) {
        if (playerId != null) {
            sessions.remove(playerId);
            skipCloseCleanup.remove(playerId);
            lastClickAt.remove(playerId);
        }
    }

    @Override
    public int sessionCount() {
        return sessions.size();
    }

    @Override
    public int sessionCount(Plugin plugin) {
        if (plugin == null) {
            return sessionCount();
        }
        String name = plugin.getName();
        return (int) sessions.values().stream().filter(session -> session.pluginName().equalsIgnoreCase(name)).count();
    }

    @Override
    public GuiStats stats() {
        return new GuiStats(
                sessions.size(),
                skipCloseCleanup.size(),
                lastClickAt.size(),
                blockedRapidClicks.get(),
                expiredSessionCleanups.get(),
                failedOpens.get()
        );
    }

    @Override
    public void cleanup(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        if (plugin.equals(platformPlugin)) {
            sessions.clear();
            skipCloseCleanup.clear();
            lastClickAt.clear();
            itemRenderer.clear();
            cleanupTask.cancelQuietly();
            return;
        }
        String name = plugin.getName();
        sessions.entrySet().removeIf(entry -> {
            boolean match = entry.getValue().pluginName().equalsIgnoreCase(name);
            if (match) {
                skipCloseCleanup.remove(entry.getKey());
                lastClickAt.remove(entry.getKey());
            }
            return match;
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof PlatformGuiHolder holder)) {
            return;
        }
        if (holder.getInventory() != event.getView().getTopInventory()) {
            event.setCancelled(true);
            return;
        }

        int topSize = topSize(holder);
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topSize) {
            if (holder.gui().cancelBottomClicks()) {
                event.setCancelled(true);
            }
            return;
        }

        event.setCancelled(true);
        if (isUnsafeClick(event)) {
            playGuiCue(holder.gui().plugin(), player, "gui-denied", Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.55F, 1.0F, 120L);
            return;
        }
        GuiSession active = sessions.get(player.getUniqueId());
        if (active == null || !active.sameIdentity(holder.session()) || !active.belongsTo(holder.session().pluginName(), holder.session().menuId())) {
            return;
        }
        if (isExpired(active)) {
            cleanup(player.getUniqueId());
            languages.send(player, holder.gui().plugin(), "gui.expired", Map.of("menu", holder.gui().menuId()));
            playGuiCue(holder.gui().plugin(), player, "gui-expired", Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.65F, 0.8F, 150L);
            player.closeInventory();
            return;
        }
        if (!acquireClick(player.getUniqueId())) {
            playGuiCue(holder.gui().plugin(), player, "gui-denied", Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.45F, 1.15F, 120L);
            return;
        }
        GuiButton button = holder.gui().buttons().get(rawSlot);
        if (button == null || button.action() == null) {
            return;
        }
        try {
            ItemStack clicked = event.getCurrentItem() == null ? null : event.getCurrentItem().clone();
            playGuiCue(holder.gui().plugin(), player, "gui-click", Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.75F, 1.0F, 70L);
            button.action().accept(new GuiClick(player, active, rawSlot, event.getClick(), clicked, event));
        } catch (Throwable error) {
            holder.gui().plugin().getLogger().log(Level.WARNING, "GUI action failed: " + holder.gui().menuId() + " slot=" + rawSlot, error);
            languages.send(player, holder.gui().plugin(), "gui.error", Map.of("menu", holder.gui().menuId()));
            playGuiCue(holder.gui().plugin(), player, "gui-error", Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.7F, 1.0F, 150L);
        }
    }

    private boolean isUnsafeClick(InventoryClickEvent event) {
        ClickType click = event.getClick();
        InventoryAction action = event.getAction();
        return event.isShiftClick()
                || click == ClickType.NUMBER_KEY
                || click == ClickType.SWAP_OFFHAND
                || click == ClickType.DOUBLE_CLICK
                || click == ClickType.DROP
                || click == ClickType.CONTROL_DROP
                || click == ClickType.CREATIVE
                || click == ClickType.UNKNOWN
                || action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.HOTBAR_SWAP
                || "HOTBAR_MOVE_AND_READD".equals(action.name())
                || action == InventoryAction.COLLECT_TO_CURSOR
                || action == InventoryAction.CLONE_STACK
                || action == InventoryAction.DROP_ALL_CURSOR
                || action == InventoryAction.DROP_ONE_CURSOR
                || action == InventoryAction.DROP_ALL_SLOT
                || action == InventoryAction.DROP_ONE_SLOT
                || action == InventoryAction.UNKNOWN;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PlatformGuiHolder holder)) {
            return;
        }
        if (holder.getInventory() != event.getView().getTopInventory()) {
            event.setCancelled(true);
            return;
        }
        if (GuiInteractionPolicy.cancelDrag(
                holder.gui().cancelBottomClicks(), topSize(holder), event.getRawSlots())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!(event.getView().getTopInventory().getHolder() instanceof PlatformGuiHolder holder)) {
            return;
        }
        if (skipCloseCleanup.remove(uuid)) {
            return;
        }
        GuiSession active = sessions.get(uuid);
        boolean cleanup = active != null && active.sameIdentity(holder.session());
        if (cleanup) {
            sessions.remove(uuid);
            lastClickAt.remove(uuid);
        }
        if (event.getPlayer() instanceof Player player) {
            try {
                holder.gui().closeHandler().accept(new GuiClose(player, holder.session(), event, cleanup));
            } catch (Throwable error) {
                holder.gui().plugin().getLogger().log(Level.WARNING, "GUI close handler failed: " + holder.gui().menuId(), error);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer().getUniqueId());
    }

    private int topSize(PlatformGuiHolder holder) {
        return holder.getInventory() == null ? normalizeSize(holder.gui().size()) : holder.getInventory().getSize();
    }

    private int normalizeSize(int value) {
        return InventoryTitleNormalizer.normalizeSize(value);
    }

    private boolean acquireClick(UUID playerId) {
        long debounce = Math.max(0L, platformPlugin.getConfig().getLong("gui.click-debounce-ms", 150L));
        if (debounce <= 0L) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long previous = lastClickAt.get(playerId);
        // Only an accepted click starts a new debounce window. Updating the
        // timestamp on blocked clicks locked out players clicking faster than
        // the window: every spam click kept resetting it and nothing got through.
        if (previous != null && now - previous < debounce) {
            blockedRapidClicks.incrementAndGet();
            return false;
        }
        lastClickAt.put(playerId, now);
        return true;
    }

    private void playGuiCue(Plugin owner, Player player, String cueKey, Sound sound, SoundCategory category,
                            float volume, float pitch, long cooldownMillis) {
        sounds.playCue(owner == null ? platformPlugin : owner, player, cueKey,
                SoundCue.of(sound, category, volume, pitch).withCooldownMillis(cooldownMillis));
    }

    private boolean isExpired(GuiSession session) {
        long ttlSeconds = platformPlugin.getConfig().getLong("gui.session-ttl-seconds", 300L);
        if (ttlSeconds <= 0L || session == null) {
            return false;
        }
        return Duration.between(session.openedAt(), Instant.now()).toSeconds() > ttlSeconds;
    }

    private void cleanupExpiredSessions() {
        sessions.entrySet().removeIf(entry -> {
            boolean expired = isExpired(entry.getValue());
            if (expired) {
                skipCloseCleanup.remove(entry.getKey());
                lastClickAt.remove(entry.getKey());
                expiredSessionCleanups.incrementAndGet();
            }
            return expired;
        });
    }

    private void runForPlayer(Player player, Runnable action) {
        if (scheduler.isOwnedByCurrentRegion(player)) {
            action.run();
            return;
        }
        scheduler.entityOperation(platformPlugin, player, () -> {
            action.run();
            return null;
        });
    }
}
