package vn.ledat.platform.api.dialog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Version-neutral platform dialog model. Child plugins should build this object
 * instead of referencing Paper dialog classes directly so LeDatPlatform can
 * choose native Paper dialog or inventory fallback per server/player.
 */
public final class PlatformDialog {
    private final Plugin plugin;
    private final String dialogId;
    private final Component title;
    private final Component inventoryTitle;
    private final List<ItemBodySpec> itemBodies;
    private final List<Component> body;
    private final List<DialogButton> buttons;
    private final List<DialogInputSpec> inputs;
    private final DialogDisplayMode mode;
    private final boolean canCloseWithEscape;
    private final int nativeColumns;
    private final int nativeButtonWidth;
    private final int fallbackSize;
    private final int fallbackInfoSlot;
    private final ItemStack fallbackFillItem;
    private final ItemStack fallbackInfoItem;
    private final Map<String, Object> context;
    private final Consumer<DialogClose> closeHandler;

    private PlatformDialog(Builder builder) {
        this.plugin = builder.plugin;
        this.dialogId = sanitize(builder.dialogId);
        this.title = builder.title == null ? Component.text(this.dialogId) : builder.title;
        this.inventoryTitle = builder.inventoryTitle == null ? this.title : builder.inventoryTitle;
        this.itemBodies = List.copyOf(builder.itemBodies);
        this.body = List.copyOf(builder.body);
        this.buttons = List.copyOf(builder.buttons);
        this.inputs = List.copyOf(builder.inputs);
        this.mode = builder.mode == null ? DialogDisplayMode.AUTO : builder.mode;
        this.canCloseWithEscape = builder.canCloseWithEscape;
        this.nativeColumns = Math.max(1, Math.min(4, builder.nativeColumns));
        this.nativeButtonWidth = Math.max(1, Math.min(1024, builder.nativeButtonWidth));
        this.fallbackSize = normalizeSize(builder.fallbackSize);
        this.fallbackInfoSlot = Math.max(-1, Math.min(53, builder.fallbackInfoSlot));
        this.fallbackFillItem = builder.fallbackFillItem == null ? defaultFill() : builder.fallbackFillItem.clone();
        this.fallbackInfoItem = builder.fallbackInfoItem == null ? defaultInfoItem(title, body) : builder.fallbackInfoItem.clone();
        this.context = Map.copyOf(builder.context);
        this.closeHandler = builder.closeHandler == null ? close -> { } : builder.closeHandler;
    }

    public Plugin plugin() { return plugin; }
    public String dialogId() { return dialogId; }
    public Component title() { return title; }
    /** Java inventory fallback header; native dialogs, forms and the info item use {@link #title()}. */
    public Component inventoryTitle() { return inventoryTitle; }
    public List<ItemBodySpec> itemBodies() { return itemBodies; }
    public List<Component> body() { return body; }
    public List<DialogButton> buttons() { return buttons; }
    public List<DialogInputSpec> inputs() { return inputs; }
    public DialogDisplayMode mode() { return mode; }
    public boolean canCloseWithEscape() { return canCloseWithEscape; }
    public int nativeColumns() { return nativeColumns; }
    public int nativeButtonWidth() { return nativeButtonWidth; }
    public int fallbackSize() { return fallbackSize; }
    public int fallbackInfoSlot() { return fallbackInfoSlot; }
    public ItemStack fallbackFillItem() { return fallbackFillItem.clone(); }
    public ItemStack fallbackInfoItem() { return fallbackInfoItem.clone(); }
    public Map<String, Object> context() { return context; }
    public Consumer<DialogClose> closeHandler() { return closeHandler; }

    public static Builder builder(Plugin plugin, String dialogId) {
        return new Builder(plugin, dialogId);
    }

    public static final class Builder {
        private final Plugin plugin;
        private final String dialogId;
        private Component title;
        private Component inventoryTitle;
        private final List<ItemBodySpec> itemBodies = new ArrayList<>();
        private final List<Component> body = new ArrayList<>();
        private final List<DialogButton> buttons = new ArrayList<>();
        private final List<DialogInputSpec> inputs = new ArrayList<>();
        private DialogDisplayMode mode = DialogDisplayMode.AUTO;
        private boolean canCloseWithEscape = true;
        private int nativeColumns = 2;
        private int nativeButtonWidth = 150;
        private int fallbackSize = 27;
        private int fallbackInfoSlot = 13;
        private ItemStack fallbackFillItem;
        private ItemStack fallbackInfoItem;
        private final Map<String, Object> context = new LinkedHashMap<>();
        private Consumer<DialogClose> closeHandler = close -> { };

        private Builder(Plugin plugin, String dialogId) {
            this.plugin = plugin;
            this.dialogId = dialogId == null || dialogId.isBlank() ? "dialog_" + UUID.randomUUID().toString().substring(0, 8) : dialogId;
        }

        public Builder title(Component title) { this.title = title; return this; }
        /** Overrides only the Java inventory fallback header. Null retains the semantic title. */
        public Builder inventoryTitle(Component title) { this.inventoryTitle = title; return this; }
        public Builder itemBody(ItemStack item, Component description) {
            if (item != null && !item.getType().isAir()) {
                this.itemBodies.add(new ItemBodySpec(item, description, true, true, 16, 16));
            }
            return this;
        }
        public Builder itemBody(ItemStack item, Component description, boolean showDecorations, boolean showTooltip, int width, int height) {
            if (item != null && !item.getType().isAir()) {
                this.itemBodies.add(new ItemBodySpec(item, description, showDecorations, showTooltip, width, height));
            }
            return this;
        }
        public Builder itemBody(ItemBodySpec spec) {
            if (spec != null && spec.item() != null && !spec.item().getType().isAir()) {
                this.itemBodies.add(spec);
            }
            return this;
        }
        public Builder body(Component line) { if (line != null) this.body.add(line); return this; }
        public Builder body(List<Component> lines) { if (lines != null) lines.stream().filter(java.util.Objects::nonNull).forEach(this.body::add); return this; }
        public Builder button(DialogButton button) { if (button != null) this.buttons.add(button); return this; }
        public Builder input(DialogInputSpec input) { if (input != null) this.inputs.add(input); return this; }
        public Builder confirm(DialogButton button) { return button(button); }
        public Builder cancel(DialogButton button) { return button(button); }
        public Builder mode(DialogDisplayMode mode) { this.mode = mode == null ? DialogDisplayMode.AUTO : mode; return this; }
        public Builder canCloseWithEscape(boolean value) { this.canCloseWithEscape = value; return this; }
        public Builder nativeColumns(int columns) { this.nativeColumns = columns; return this; }
        public Builder nativeButtonWidth(int width) { this.nativeButtonWidth = width; return this; }
        public Builder fallbackSize(int size) { this.fallbackSize = size; return this; }
        public Builder fallbackInfoSlot(int slot) { this.fallbackInfoSlot = slot; return this; }
        public Builder fallbackFillItem(ItemStack item) { this.fallbackFillItem = item == null ? null : item.clone(); return this; }
        public Builder fallbackInfoItem(ItemStack item) { this.fallbackInfoItem = item == null ? null : item.clone(); return this; }
        public Builder context(String key, Object value) {
            if (key == null || key.isBlank()) return this;
            if (value == null) context.remove(key); else context.put(key, value);
            return this;
        }
        public Builder onClose(Consumer<DialogClose> handler) { this.closeHandler = handler == null ? close -> { } : handler; return this; }
        public PlatformDialog build() { return new PlatformDialog(this); }
    }

    public record ItemBodySpec(
            ItemStack item,
            Component description,
            boolean showDecorations,
            boolean showTooltip,
            int width,
            int height
    ) {
        public ItemBodySpec {
            item = item == null ? new ItemStack(Material.AIR) : item.clone();
            width = Math.max(1, Math.min(256, width));
            height = Math.max(1, Math.min(256, height));
        }

        @Override
        public ItemStack item() {
            return item.clone();
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "dialog";
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    private static int normalizeSize(int value) {
        int clamped = Math.max(9, Math.min(54, value));
        int normalized = clamped - (clamped % 9);
        return normalized <= 0 ? 9 : normalized;
    }

    private static ItemStack defaultFill() {
        ItemStack item = new ItemStack(Material.AIR);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(nonItalic(Component.empty()));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Component nonItalic(Component component) {
        Component rooted = component == null ? Component.text("") : Component.text("").append(component);
        return rooted.decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack defaultInfoItem(Component title, List<Component> body) {
        ItemStack item = new ItemStack(Material.BOOK);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(nonItalic(title == null ? Component.text("Dialog") : title));
            if (body != null && !body.isEmpty()) {
                meta.lore(body.stream().limit(12).map(PlatformDialog::nonItalic).toList());
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
