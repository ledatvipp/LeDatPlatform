package vn.ledat.platform.core.gui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import net.kyori.adventure.text.Component;
import vn.ledat.platform.api.gui.GuiButton;
import vn.ledat.platform.api.gui.GuiClick;
import vn.ledat.platform.api.gui.GuiClose;
import vn.ledat.platform.api.gui.GuiService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class SimplePlatformGui implements GuiService.PlatformGui {
    private final Plugin plugin;
    private final String menuId;
    private final String titleKey;
    private final Component title;
    private final int size;
    private final int page;
    private final Map<Integer, GuiButton> buttons;
    private final Map<Integer, ItemStack> staticItems;
    private final Map<String, Object> context;
    private final ItemStack fill;
    private final boolean cancelBottomClicks;
    private final Consumer<GuiClose> closeHandler;

    SimplePlatformGui(Plugin plugin, String menuId, String titleKey, Component title, int size, int page, Map<Integer, GuiButton> buttons, Map<Integer, ItemStack> staticItems,
                      Map<String, Object> context, ItemStack fill, boolean cancelBottomClicks, Consumer<GuiClose> closeHandler) {
        this.plugin = plugin;
        this.menuId = menuId;
        this.titleKey = titleKey;
        this.title = title;
        this.size = size;
        this.page = page;
        this.buttons = Map.copyOf(buttons);
        this.staticItems = Map.copyOf(staticItems);
        this.context = Map.copyOf(context);
        this.fill = GuiService.nonItalicItem(fill);
        this.cancelBottomClicks = cancelBottomClicks;
        this.closeHandler = closeHandler == null ? close -> { } : closeHandler;
    }

    @Override public Plugin plugin() { return plugin; }
    @Override public String menuId() { return menuId; }
    @Override public int size() { return size; }
    @Override public String titleKey() { return titleKey; }
    @Override public Component title() { return title; }
    @Override public int page() { return page; }
    @Override public Map<Integer, GuiButton> buttons() { return buttons; }
    @Override public Map<Integer, ItemStack> staticItems() { return staticItems; }
    @Override public Map<String, Object> context() { return context; }
    @Override public boolean cancelBottomClicks() { return cancelBottomClicks; }
    @Override public Consumer<GuiClose> closeHandler() { return closeHandler; }
    ItemStack fill() { return GuiService.nonItalicItem(fill); }

    static final class Builder implements GuiService.PlatformGui.Builder {
        private final Plugin plugin;
        private final String menuId;
        private String titleKey = "gui.title";
        private Component title;
        private int size = 27;
        private int page = 0;
        private ItemStack fill;
        private boolean cancelBottomClicks = true;
        private Consumer<GuiClose> closeHandler = close -> { };
        private final Map<Integer, GuiButton> buttons = new LinkedHashMap<>();
        private final Map<Integer, ItemStack> staticItems = new LinkedHashMap<>();
        private final Map<String, Object> context = new ConcurrentHashMap<>();
        private final Map<Character, List<Integer>> patternSlots = new LinkedHashMap<>();

        Builder(Plugin plugin, String menuId) {
            this.plugin = plugin;
            this.menuId = menuId;
        }

        @Override public GuiService.PlatformGui.Builder titleKey(String titleKey) { this.titleKey = titleKey == null || titleKey.isBlank() ? "gui.title" : titleKey; return this; }
        @Override public GuiService.PlatformGui.Builder title(Component title) { this.title = title; return this; }
        @Override public GuiService.PlatformGui.Builder size(int size) { this.size = normalizeSize(size); return this; }
        @Override public GuiService.PlatformGui.Builder page(int page) { this.page = Math.max(0, page); return this; }
        @Override public GuiService.PlatformGui.Builder fill(ItemStack item) { this.fill = GuiService.nonItalicItem(item); return this; }
        @Override public GuiService.PlatformGui.Builder cancelBottomClicks(boolean cancel) { this.cancelBottomClicks = cancel; return this; }
        @Override public GuiService.PlatformGui.Builder onClose(Consumer<GuiClose> handler) { this.closeHandler = handler == null ? close -> { } : handler; return this; }

        @Override
        public void context(String key, Object value) {
            if (key == null || key.isBlank()) {
                return;
            }
            if (value == null) {
                context.remove(key);
            } else {
                context.put(key, value);
            }
        }

        @Override
        public GuiService.PlatformGui.Builder button(int slot, ItemStack item, Consumer<GuiClick> action) {
            return button("slot_" + slot, slot, item, action);
        }

        @Override
        public GuiService.PlatformGui.Builder button(String id, int slot, ItemStack item, Consumer<GuiClick> action) {
            if (slot >= 0 && slot < size) {
                ItemStack safeItem = GuiService.nonItalicItem(item == null ? new ItemStack(Material.BARRIER) : item);
                buttons.put(slot, new GuiButton(id == null || id.isBlank() ? "slot_" + slot : id, safeItem, action));
                staticItems.remove(slot);
            }
            return this;
        }

        @Override
        public GuiService.PlatformGui.Builder staticItem(int slot, ItemStack item) {
            if (slot >= 0 && slot < size) {
                staticItems.put(slot, GuiService.nonItalicItem(item == null ? new ItemStack(Material.BARRIER) : item));
                buttons.remove(slot);
            }
            return this;
        }

        @Override
        public GuiService.PlatformGui.Builder pattern(String... rows) {
            patternSlots.clear();
            if (rows == null || rows.length == 0) {
                return this;
            }
            int rowCount = Math.min(6, rows.length);
            this.size = rowCount * 9;
            for (int row = 0; row < rowCount; row++) {
                String value = rows[row] == null ? "" : rows[row];
                for (int column = 0; column < Math.min(9, value.length()); column++) {
                    patternSlots.computeIfAbsent(value.charAt(column), ignored -> new java.util.ArrayList<>()).add(row * 9 + column);
                }
            }
            return this;
        }

        @Override
        public GuiService.PlatformGui.Builder button(char key, ItemStack item, Consumer<GuiClick> action) {
            List<Integer> slots = patternSlots.getOrDefault(key, List.of());
            for (int slot : slots) {
                button("pattern_" + key + "_" + slot, slot, item, action);
            }
            return this;
        }

        @Override
        public GuiService.PlatformGui.Builder border(ItemStack item, Consumer<GuiClick> action) {
            int rows = Math.max(1, size / 9);
            for (int column = 0; column < 9; column++) {
                button("border_top_" + column, column, item, action);
                if (rows > 1) button("border_bottom_" + column, (rows - 1) * 9 + column, item, action);
            }
            for (int row = 1; row < rows - 1; row++) {
                button("border_left_" + row, row * 9, item, action);
                button("border_right_" + row, row * 9 + 8, item, action);
            }
            return this;
        }

        @Override
        public GuiService.PlatformGui.Builder paginated(List<ItemStack> items, int[] contentSlots, Consumer<GuiClick> itemAction) {
            if (items == null || contentSlots == null || contentSlots.length == 0) {
                return this;
            }
            int start = Math.max(0, page) * contentSlots.length;
            for (int i = 0; i < contentSlots.length; i++) {
                int itemIndex = start + i;
                if (itemIndex >= items.size()) {
                    break;
                }
                button("item_" + itemIndex, contentSlots[i], items.get(itemIndex), itemAction);
            }
            context("pagination.page", page);
            context("pagination.page-size", contentSlots.length);
            context("pagination.total-items", items.size());
            context("pagination.total-pages", Math.max(1, (int) Math.ceil(items.size() / (double) contentSlots.length)));
            return this;
        }

        @Override
        public GuiService.PlatformGui build() {
            return new SimplePlatformGui(plugin, menuId, titleKey, title, size, page, buttons, staticItems, context, fill, cancelBottomClicks, closeHandler);
        }

        private int normalizeSize(int value) {
            int clamped = Math.max(9, Math.min(54, value));
            int normalized = clamped - (clamped % 9);
            return normalized <= 0 ? 9 : normalized;
        }
    }
}
