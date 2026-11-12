package vn.ledat.platform.api.bedrockform;

import net.kyori.adventure.text.Component;

import java.util.Locale;
import java.util.function.Consumer;

public record BedrockFormButton(
        String id,
        Component label,
        Consumer<BedrockFormClick> action
) {
    public BedrockFormButton {
        id = sanitize(id);
        label = label == null ? Component.text(id) : label;
        action = action == null ? click -> { } : action;
    }

    public static BedrockFormButton of(String id, Component label, Consumer<BedrockFormClick> action) {
        return new BedrockFormButton(id, label, action);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "button";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }
}
