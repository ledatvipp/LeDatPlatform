package vn.ledat.platform.core.gui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerProfile;
import vn.ledat.platform.api.gui.GuiItemDefinition;
import vn.ledat.platform.api.gui.GuiService;
import vn.ledat.platform.api.profile.GameProfileService;
import vn.ledat.platform.api.scheduler.LeDatScheduler;

import java.net.URL;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

final class GuiItemRenderer {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");
    private static final long TEXTURE_TTL = Duration.ofHours(6).toNanos();
    private static final int MAXIMUM_TEXTURES = 512;
    private final GameProfileService profiles;
    private final LeDatScheduler scheduler;
    private final Map<String, TextureItem> textures = new ConcurrentHashMap<>();

    GuiItemRenderer(GameProfileService profiles, LeDatScheduler scheduler) {
        this.profiles = profiles;
        this.scheduler = scheduler;
    }

    CompletableFuture<ItemStack> render(Plugin owner, Player viewer, GuiItemDefinition definition,
                                       Map<String, String> placeholders) {
        if (owner == null || viewer == null || definition == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Owner, viewer and item definition are required"));
        }
        Map<String, String> values = new HashMap<>();
        if (placeholders != null) placeholders.forEach((key, value) -> values.put(key, value == null ? "" : value));
        return owned(owner, viewer, () -> {
            values.putIfAbsent("player", viewer.getName());
            values.putIfAbsent("player_uuid", viewer.getUniqueId().toString());
            GuiItemDefinition.Head head = definition.head();
            if (head == null || head.source() != GuiItemDefinition.HeadSource.PLAYER) {
                return CompletableFuture.completedFuture(decorate(textureItem(definition, values), definition, values));
            }
            String rawUuid = replace(head.uuid(), values, false);
            String name = replace(head.nameFallback(), values, false);
            UUID playerId;
            try {
                playerId = rawUuid.isBlank() ? null : UUID.fromString(rawUuid);
            } catch (IllegalArgumentException invalidUuid) {
                return CompletableFuture.completedFuture(decorate(fallback(head), definition, values));
            }
            if (playerId == null && !name.matches("[A-Za-z0-9_]{1,16}")) {
                return CompletableFuture.completedFuture(decorate(fallback(head), definition, values));
            }
            CompletableFuture<Optional<PlayerProfile>> lookup;
            try {
                // A derived stage prevents a timeout from completing the service's shared in-flight future.
                lookup = profiles.resolve(playerId, name).thenApply(profile -> profile)
                        .completeOnTimeout(Optional.empty(), 3L, TimeUnit.SECONDS)
                        .exceptionally(error -> Optional.empty());
            } catch (RuntimeException failure) {
                lookup = CompletableFuture.completedFuture(Optional.empty());
            }
            return lookup.thenCompose(profile -> owned(owner, viewer, () -> {
                ItemStack item = profile.isEmpty() ? fallback(head) : new ItemStack(Material.PLAYER_HEAD);
                if (profile.isPresent() && item.getItemMeta() instanceof SkullMeta meta) {
                    meta.setOwnerProfile(profile.get().clone());
                    item.setItemMeta(meta);
                }
                return decorate(item, definition, values);
            }));
        }).thenCompose(result -> result);
    }

    void clear() { textures.clear(); }

    private ItemStack textureItem(GuiItemDefinition definition, Map<String, String> values) {
        GuiItemDefinition.Head head = definition.head();
        if (head == null) return new ItemStack(definition.material());
        Optional<URL> url = GuiHeadTexture.normalize(head.source(), replace(head.value(), values, false));
        if (url.isEmpty()) return fallback(head);
        String key = url.get().toExternalForm();
        long now = System.nanoTime();
        TextureItem cached = textures.get(key);
        if (cached != null && now - cached.createdAt() < TEXTURE_TTL) return cached.item().clone();
        try {
            ItemStack item = profiles.texturedHead(url.get(), null);
            if (item == null || item.getType() != Material.PLAYER_HEAD) return fallback(head);
            textures.put(key, new TextureItem(now, item.clone()));
            if (textures.size() > MAXIMUM_TEXTURES) {
                textures.entrySet().removeIf(entry -> now - entry.getValue().createdAt() >= TEXTURE_TTL);
                while (textures.size() > MAXIMUM_TEXTURES) {
                    String oldestCandidate = textures.keySet().stream().findFirst().orElse(null);
                    if (oldestCandidate == null) break;
                    textures.remove(oldestCandidate);
                }
            }
            return item.clone();
        } catch (RuntimeException failure) {
            return fallback(head);
        }
    }

    private static ItemStack fallback(GuiItemDefinition.Head head) {
        return new ItemStack(head.fallbackMaterial());
    }

    private ItemStack decorate(ItemStack item, GuiItemDefinition definition, Map<String, String> values) {
        ItemStack copy = item.clone();
        copy.setAmount(definition.amount());
        var meta = copy.getItemMeta();
        if (meta != null) {
            if (definition.name() != null) meta.displayName(GuiService.nonItalic(MINI.deserialize(replace(definition.name(), values, true))));
            if (!definition.lore().isEmpty()) meta.lore(definition.lore().stream()
                    .map(line -> GuiService.nonItalic(MINI.deserialize(replace(line, values, true)))).toList());
            if (definition.customModelData() != null) meta.setCustomModelData(definition.customModelData());
            if (definition.itemModel() != null && !definition.itemModel().isBlank()) {
                NamespacedKey model = NamespacedKey.fromString(definition.itemModel());
                if (model == null) throw new IllegalArgumentException("Invalid GUI item-model key");
                meta.setItemModel(model);
            }
            copy.setItemMeta(meta);
        }
        return GuiService.nonItalicItem(copy);
    }

    static String replace(String template, Map<String, String> values, boolean escapeTags) {
        if (template == null) return "";
        return PLACEHOLDER.matcher(template).replaceAll(match -> {
            String key = match.group(1);
            String value = values.get(key);
            if (value == null) throw new IllegalArgumentException("Missing GUI item placeholder {" + key + "}");
            if (value.length() > 16384 || value.chars().anyMatch(character -> character < 32 || character == 127)) {
                throw new IllegalArgumentException("Invalid GUI item placeholder {" + key + "}");
            }
            return java.util.regex.Matcher.quoteReplacement(escapeTags ? MINI.escapeTags(value) : value);
        });
    }

    private <T> CompletableFuture<T> owned(Plugin owner, Player viewer, Supplier<T> operation) {
        return scheduler.entityOperation(owner, viewer, () -> {
            if (!owner.isEnabled() || !viewer.isOnline()) throw new CancellationException("GUI item viewer retired");
            return operation.get();
        }).thenCompose(outcome -> {
            if (outcome.executed()) return CompletableFuture.completedFuture(outcome.value());
            return CompletableFuture.failedFuture(outcome.error() == null
                    ? new CancellationException("GUI item owner unavailable") : outcome.error());
        });
    }

    private record TextureItem(long createdAt, ItemStack item) { }
}
