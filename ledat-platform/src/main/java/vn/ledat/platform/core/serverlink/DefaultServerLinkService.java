package vn.ledat.platform.core.serverlink;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.scheduler.LeDatScheduler;
import vn.ledat.platform.api.serverlink.ServerLinkEntry;
import vn.ledat.platform.api.serverlink.ServerLinkService;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Version-safe wrapper around Bukkit/Paper ServerLinks.
 *
 * <p>Server links are what makes the vanilla ESC "Server Links..." button
 * appear. They can open URLs, not server commands; command/dialog entry points
 * should be exposed by regular commands such as /settings dialog.</p>
 */
public final class DefaultServerLinkService implements ServerLinkService, Listener {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Plugin plugin;
    private final LeDatScheduler scheduler;
    private final AtomicInteger sent = new AtomicInteger();
    private volatile List<ServerLinkEntry> entries = List.of();
    private volatile boolean available;
    private volatile String status = "not-initialized";

    public DefaultServerLinkService(Plugin plugin, LeDatScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        reload();
    }

    @Override
    public void reload() {
        List<ServerLinkEntry> loaded = new ArrayList<>();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("server-links.entries");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null || !section.getBoolean("enabled", true)) {
                    continue;
                }
                String url = section.getString("url", "").trim();
                if (url.isBlank()) {
                    plugin.getLogger().warning("server-links.entries." + id + ".url is blank; skipped");
                    continue;
                }
                try {
                    URI.create(url);
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("server-links.entries." + id + ".url is invalid: " + url);
                    continue;
                }
                String rawLabel = section.getString("label", id);
                Component label = parseLabel(rawLabel == null ? id : rawLabel);
                loaded.add(new ServerLinkEntry(id, label, url, section.getString("permission", ""), true));
            }
        }
        this.entries = Collections.unmodifiableList(loaded);
        this.available = detectAvailable();
        this.status = available ? "Bukkit ServerLinks API available; entries=" + loaded.size() : "Bukkit ServerLinks API unavailable on this runtime";
        if (enabled() && available && plugin.getConfig().getBoolean("server-links.apply-all-on-reload", true)) {
            applyAllOnline();
        }
    }

    @Override
    public void apply(Player player) {
        if (player == null || !enabled() || !available) {
            return;
        }
        scheduler.entity(plugin, player, () -> applyOnEntityThread(player));
    }

    @Override
    public void applyAllOnline() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            return;
        }
        int batchSize = Math.max(1, plugin.getConfig().getInt("server-links.apply-all-batch-size", 25));
        long initialDelay = Math.max(1L, plugin.getConfig().getLong("server-links.apply-all-initial-delay-ticks", 20L));
        long staggerTicks = Math.max(0L, plugin.getConfig().getLong("server-links.apply-all-stagger-ticks", 2L));
        for (int start = 0; start < players.size(); start += batchSize) {
            int from = start;
            int to = Math.min(players.size(), start + batchSize);
            long delay = initialDelay + ((long) (start / batchSize) * staggerTicks);
            scheduler.later(plugin, () -> {
                for (int index = from; index < to; index++) {
                    apply(players.get(index));
                }
            }, delay);
        }
    }

    private void applyOnEntityThread(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            Object baseLinks = getServerLinks();
            if (baseLinks == null) {
                return;
            }
            Object links = invokeNoArgs(baseLinks, "copy");
            int added = 0;
            for (ServerLinkEntry entry : entries) {
                if (!entry.enabled()) {
                    continue;
                }
                if (entry.permission() != null && !entry.permission().isBlank() && !player.hasPermission(entry.permission())) {
                    continue;
                }
                addLink(links, renderLabel(entry.label(), player), URI.create(renderUrl(entry.url(), player)));
                added++;
            }
            if (added == 0) {
                return;
            }
            Method sendLinks = findSendLinks(player.getClass(), links.getClass());
            if (sendLinks == null) {
                status = "Player#sendLinks(ServerLinks) not found";
                return;
            }
            sendLinks.invoke(player, links);
            sent.incrementAndGet();
        } catch (Throwable error) {
            status = error.getClass().getSimpleName() + ": " + error.getMessage();
            plugin.getLogger().log(Level.WARNING, "Could not send configured server links to " + player.getName(), error);
        }
    }

    private boolean detectAvailable() {
        try {
            Object links = getServerLinks();
            return links != null && findAddLink(links.getClass()) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object getServerLinks() throws ReflectiveOperationException {
        try {
            return Bukkit.class.getMethod("getServerLinks").invoke(null);
        } catch (NoSuchMethodException ignored) {
            return Bukkit.getServer().getClass().getMethod("getServerLinks").invoke(Bukkit.getServer());
        }
    }

    private void addLink(Object links, Component label, URI uri) throws ReflectiveOperationException {
        Method add = findAddLink(links.getClass());
        if (add == null) {
            throw new NoSuchMethodException("ServerLinks#addLink(Component|String, URI)");
        }
        Class<?> first = add.getParameterTypes()[0];
        if (Component.class.isAssignableFrom(first)) {
            add.invoke(links, label, uri);
        } else {
            add.invoke(links, PLAIN.serialize(label), uri);
        }
    }

    private Method findAddLink(Class<?> linksClass) {
        Method stringFallback = null;
        for (Method method : linksClass.getMethods()) {
            if (!method.getName().equals("addLink") || method.getParameterCount() != 2) {
                continue;
            }
            if (!URI.class.isAssignableFrom(method.getParameterTypes()[1])) {
                continue;
            }
            Class<?> first = method.getParameterTypes()[0];
            if (Component.class.isAssignableFrom(first)) {
                return method;
            }
            if (String.class.isAssignableFrom(first)) {
                stringFallback = method;
            }
        }
        return stringFallback;
    }

    private Method findSendLinks(Class<?> playerClass, Class<?> linksClass) {
        for (Method method : playerClass.getMethods()) {
            if (!method.getName().equals("sendLinks") || method.getParameterCount() != 1) {
                continue;
            }
            if (method.getParameterTypes()[0].isAssignableFrom(linksClass)) {
                return method;
            }
        }
        return null;
    }

    private Object invokeNoArgs(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private Component renderLabel(Component component, Player player) {
        if (component == null) {
            return Component.empty();
        }
        String serialized = MINI.serialize(component);
        return parseLabel(renderText(serialized, player));
    }

    private Component parseLabel(String rawLabel) {
        String label = rawLabel == null || rawLabel.isBlank() ? "Link" : rawLabel;
        try {
            return MINI.deserialize(label);
        } catch (Throwable ignored) {
            return Component.text(label.replaceAll("<[^>]+>", ""));
        }
    }

    private String renderUrl(String text, Player player) {
        return renderText(text, player).replace(" ", "%20");
    }

    private String renderText(String text, Player player) {
        if (text == null) {
            return "";
        }
        return text.replace("{player}", player == null ? "" : player.getName())
                .replace("{uuid}", player == null ? "" : player.getUniqueId().toString())
                .replace("{server}", plugin.getConfig().getString("server-links.server-name", Bukkit.getServer().getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled()) {
            return;
        }
        long delay = Math.max(1L, plugin.getConfig().getLong("server-links.resend-on-join-delay-ticks", 20L));
        scheduler.later(plugin, () -> apply(event.getPlayer()), delay);
    }

    @Override public boolean available() { return available; }
    @Override public boolean enabled() { return plugin.getConfig().getBoolean("server-links.enabled", true); }
    @Override public String status() { return status; }
    @Override public int configuredCount() { return entries.size(); }
    @Override public int sentCount() { return sent.get(); }
    @Override public List<ServerLinkEntry> entries() { return entries; }
}
