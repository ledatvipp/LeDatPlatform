package vn.ledat.platform.api.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;

public interface GuiService {
    PlatformGui.Builder gui(Plugin plugin, String menuId);

    /**
     * Normalize an item before it is used inside a platform GUI.
     *
     * Minecraft renders item display names and lore as italic unless the
     * component explicitly disables italics. Child plugins can pass items from
     * config, language, or legacy text helpers here so GUI item text keeps the
     * suite-wide non-italic style.
     */
    default ItemStack item(ItemStack item) {
        return nonItalicItem(item);
    }

    /**
     * Render an immutable config item with escaped ordinary placeholders and owner-scheduled profile/meta work.
     * This does not update an inventory. Before applying an asynchronous result, callers must recheck their
     * current viewer/session/menu, render revision and stable slot key on the player owner scheduler.
     */
    default CompletableFuture<ItemStack> renderItem(Plugin owner, Player viewer, GuiItemDefinition definition,
                                                   Map<String, String> placeholders) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Config GUI item rendering is unavailable"));
    }

    /**
     * Apply the suite-wide item text style. This intentionally clones the item
     * and only touches display name/lore decorations, preserving material, PDC,
     * enchants, model data, flags, and custom meta.
     */
    static ItemStack nonItalicItem(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemStack copy = item.clone();
        if (!copy.hasItemMeta()) {
            return copy;
        }
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) {
            return copy;
        }
        Component displayName = meta.displayName();
        if (displayName != null) {
            meta.displayName(nonItalic(displayName));
        }
        List<Component> lore = meta.lore();
        if (lore != null) {
            List<Component> normalized = new ArrayList<>(lore.size());
            for (Component line : lore) {
                normalized.add(nonItalic(line));
            }
            meta.lore(normalized);
        }
        copy.setItemMeta(meta);
        return copy;
    }

    /**
     * Force a component to opt out of Minecraft's default item italic style.
     */
    static Component nonItalic(Component component) {
        Component rooted = component == null ? Component.text("") : Component.text("").append(component);
        return rooted.decoration(TextDecoration.ITALIC, false);
    }

    void open(Player player, PlatformGui gui);

    default void open(Player player, PlatformGui gui, Map<String, Object> context) {
        if (gui instanceof MutableContext mutable && context != null) {
            context.forEach(mutable::context);
        }
        open(player, gui);
    }

    void close(Player player, boolean clearSession);

    /**
     * Close only the still-current platform GUI session. Implementations should
     * recheck the session identity on the player owner before closing so a late
     * cleanup cannot close a replacement menu.
     */
    default void closeSession(UUID playerId, UUID expectedSessionId) {
    }

    GuiSession session(UUID playerId);
    void cleanup(UUID playerId);

    default int sessionCount() { return 0; }
    default int sessionCount(Plugin plugin) { return 0; }
    default void cleanup(Plugin plugin) { }

    /**
     * Runtime snapshot for GUI leak/pressure diagnostics.
     */
    default GuiStats stats() {
        return new GuiStats(0, 0, 0, 0L, 0L, 0L);
    }

    interface MutableContext {
        void context(String key, Object value);
    }

    interface PlatformGui {
        Plugin plugin();
        String menuId();
        int size();
        String titleKey();
        default Component title() { return null; }
        int page();
        Map<Integer, GuiButton> buttons();

        /** Read-only items rendered in the inventory without a click handler. */
        default Map<Integer, ItemStack> staticItems() { return Map.of(); }

        default Map<String, Object> context() { return Map.of(); }
        default boolean cancelBottomClicks() { return true; }
        default Consumer<GuiClose> closeHandler() { return close -> { }; }

        interface Builder extends MutableContext {
            Builder titleKey(String titleKey);
            default Builder title(Component title) { return this; }
            Builder size(int size);
            Builder page(int page);
            Builder fill(ItemStack item);
            Builder button(int slot, ItemStack item, Consumer<GuiClick> action);

            /**
             * Render information at a slot without registering a clickable button.
             * Use for status, rankings, labels and decoration; use {@link #button} only for real actions.
             */
            default Builder staticItem(int slot, ItemStack item) { return this; }

            default Builder button(String id, int slot, ItemStack item, Consumer<GuiClick> action) {
                return button(slot, item, action);
            }

            /** Define a 9-column character layout. Each string is one inventory row. */
            default Builder pattern(String... rows) { return this; }

            /** Apply an already validated layout without changing the legacy pattern validation contract. */
            default Builder layout(GuiLayout layout) {
                java.util.Objects.requireNonNull(layout, "layout");
                pattern(layout.rows().toArray(String[]::new));
                return size(layout.size());
            }

            /** Apply one button to every slot mapped to the character by {@link #pattern(String...)}. */
            default Builder button(char key, ItemStack item, Consumer<GuiClick> action) { return this; }

            default Builder row(int row, ItemStack item, Consumer<GuiClick> action) {
                int safeRow = Math.max(0, Math.min(5, row));
                for (int column = 0; column < 9; column++) button(safeRow * 9 + column, item, action);
                return this;
            }

            default Builder border(ItemStack item, Consumer<GuiClick> action) {
                for (int slot = 0; slot < 9; slot++) button(slot, item, action);
                for (int slot = 0; slot < 9; slot++) button(45 + slot, item, action);
                for (int row = 1; row < 5; row++) {
                    button(row * 9, item, action);
                    button(row * 9 + 8, item, action);
                }
                return this;
            }

            Builder paginated(List<ItemStack> items, int[] contentSlots, Consumer<GuiClick> itemAction);

            default Builder cancelBottomClicks(boolean cancel) { return this; }
            default Builder onClose(Consumer<GuiClose> handler) { return this; }
            @Override default void context(String key, Object value) { }
            PlatformGui build();
        }
    }

    record GuiStats(
            int sessions,
            int reopenGuards,
            int clickDebounceEntries,
            long blockedRapidClicks,
            long expiredSessionCleanups,
            long failedOpens
    ) {
    }
}
