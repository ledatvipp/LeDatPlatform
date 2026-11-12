package vn.ledat.platform.api.dialog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A version-safe dialog input definition. Native Paper dialogs will render these
 * as real client inputs; inventory fallback renders them as read-only guidance
 * items so the same template still looks intentional on older clients.
 */
public record DialogInputSpec(
        String key,
        DialogInputType type,
        Component label,
        boolean labelVisible,
        int width,
        String initial,
        int maxLength,
        boolean initialBoolean,
        String onTrue,
        String onFalse,
        float min,
        float max,
        Float initialNumber,
        Float step,
        String labelFormat,
        List<Option> options,
        int fallbackSlot,
        ItemStack fallbackItem
) {
    public DialogInputSpec {
        key = sanitize(key);
        type = type == null ? DialogInputType.TEXT : type;
        label = label == null ? Component.text(key) : label;
        width = Math.max(1, Math.min(1024, width <= 0 ? 300 : width));
        initial = initial == null ? "" : initial;
        maxLength = Math.max(1, Math.min(1024, maxLength <= 0 ? 64 : maxLength));
        onTrue = onTrue == null ? "true" : onTrue;
        onFalse = onFalse == null ? "false" : onFalse;
        if (max < min) {
            float swap = min;
            min = max;
            max = swap;
        }
        labelFormat = labelFormat == null || labelFormat.isBlank() ? "%s: %s" : labelFormat;
        options = options == null ? List.of() : List.copyOf(options);
        fallbackSlot = Math.max(-1, Math.min(53, fallbackSlot));
        fallbackItem = fallbackItem == null ? defaultFallbackItem(type, label) : fallbackItem.clone();
    }

    public static Builder builder(String key, DialogInputType type) {
        return new Builder(key, type);
    }

    public static Builder text(String key, Component label) {
        return builder(key, DialogInputType.TEXT).label(label);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "input";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    private static ItemStack defaultFallbackItem(DialogInputType type, Component label) {
        Material material = switch (type == null ? DialogInputType.TEXT : type) {
            case TEXT -> Material.NAME_TAG;
            case BOOLEAN -> Material.LEVER;
            case SINGLE_OPTION -> Material.COMPASS;
            case NUMBER_RANGE -> Material.CLOCK;
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(nonItalic(label == null ? Component.text("Input") : label));
            meta.lore(List.of(nonItalic(Component.text("Native dialog input; fallback menu is read-only."))));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Component nonItalic(Component component) {
        Component rooted = component == null ? Component.text("") : Component.text("").append(component);
        return rooted.decoration(TextDecoration.ITALIC, false);
    }

    public record Option(String value, Component label, boolean initial) {
        public Option {
            value = value == null ? "" : value;
            label = label == null ? Component.text(value) : label;
        }
    }

    public static final class Builder {
        private final String key;
        private final DialogInputType type;
        private Component label;
        private boolean labelVisible = true;
        private int width = 300;
        private String initial = "";
        private int maxLength = 64;
        private boolean initialBoolean;
        private String onTrue = "true";
        private String onFalse = "false";
        private float min = 0f;
        private float max = 100f;
        private Float initialNumber;
        private Float step;
        private String labelFormat = "%s: %s";
        private final List<Option> options = new ArrayList<>();
        private int fallbackSlot = -1;
        private ItemStack fallbackItem;

        private Builder(String key, DialogInputType type) {
            this.key = key;
            this.type = type;
        }

        public Builder label(Component label) { this.label = label; return this; }
        public Builder labelVisible(boolean labelVisible) { this.labelVisible = labelVisible; return this; }
        public Builder width(int width) { this.width = width; return this; }
        public Builder initial(String initial) { this.initial = initial; return this; }
        public Builder maxLength(int maxLength) { this.maxLength = maxLength; return this; }
        public Builder initialBoolean(boolean initialBoolean) { this.initialBoolean = initialBoolean; return this; }
        public Builder booleanValues(String onTrue, String onFalse) { this.onTrue = onTrue; this.onFalse = onFalse; return this; }
        public Builder range(float min, float max) { this.min = min; this.max = max; return this; }
        public Builder initialNumber(Float initialNumber) { this.initialNumber = initialNumber; return this; }
        public Builder step(Float step) { this.step = step; return this; }
        public Builder labelFormat(String labelFormat) { this.labelFormat = labelFormat; return this; }
        public Builder option(String value, Component label, boolean initial) { this.options.add(new Option(value, label, initial)); return this; }
        public Builder fallbackSlot(int fallbackSlot) { this.fallbackSlot = fallbackSlot; return this; }
        public Builder fallbackItem(ItemStack fallbackItem) { this.fallbackItem = fallbackItem == null ? null : fallbackItem.clone(); return this; }

        public DialogInputSpec build() {
            return new DialogInputSpec(key, type, label, labelVisible, width, initial, maxLength,
                    initialBoolean, onTrue, onFalse, min, max, initialNumber, step,
                    labelFormat, options, fallbackSlot, fallbackItem);
        }
    }
}
