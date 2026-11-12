package vn.ledat.platform.api.bedrockform;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Locale;

public record BedrockFormInput(
        String key,
        BedrockFormInputType type,
        Component label,
        String placeholder,
        String initial,
        boolean initialBoolean,
        List<String> options,
        int defaultOption,
        float min,
        float max,
        float step,
        float initialNumber
) {
    public BedrockFormInput {
        key = sanitize(key);
        type = type == null ? BedrockFormInputType.TEXT : type;
        label = label == null ? Component.text(key) : label;
        placeholder = placeholder == null ? "" : placeholder;
        initial = initial == null ? "" : initial;
        options = options == null ? List.of() : List.copyOf(options);
        defaultOption = Math.max(0, Math.min(Math.max(0, options.size() - 1), defaultOption));
        if (max < min) {
            float swap = min;
            min = max;
            max = swap;
        }
        step = step <= 0f ? 1f : step;
        initialNumber = Math.max(min, Math.min(max, initialNumber));
    }

    public static BedrockFormInput label(Component label) {
        return new BedrockFormInput("label", BedrockFormInputType.LABEL, label, "", "", false, List.of(), 0, 0f, 0f, 1f, 0f);
    }

    public static BedrockFormInput text(String key, Component label, String placeholder, String initial) {
        return new BedrockFormInput(key, BedrockFormInputType.TEXT, label, placeholder, initial, false, List.of(), 0, 0f, 0f, 1f, 0f);
    }

    public static BedrockFormInput toggle(String key, Component label, boolean initial) {
        return new BedrockFormInput(key, BedrockFormInputType.BOOLEAN, label, "", "", initial, List.of(), 0, 0f, 0f, 1f, 0f);
    }

    public static BedrockFormInput dropdown(String key, Component label, List<String> options, int defaultOption) {
        return new BedrockFormInput(key, BedrockFormInputType.DROPDOWN, label, "", "", false, options, defaultOption, 0f, 0f, 1f, 0f);
    }

    public static BedrockFormInput slider(String key, Component label, float min, float max, float step, float initial) {
        return new BedrockFormInput(key, BedrockFormInputType.SLIDER, label, "", "", false, List.of(), 0, min, max, step, initial);
    }

    public static BedrockFormInput stepSlider(String key, Component label, List<String> steps, int defaultStep) {
        return new BedrockFormInput(key, BedrockFormInputType.STEP_SLIDER, label, "", "", false, steps, defaultStep, 0f, 0f, 1f, 0f);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "input";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }
}
