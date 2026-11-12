package vn.ledat.platform.api.bedrockform;

import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;

public record BedrockFormClick(
        Player player,
        BedrockFormSession session,
        String actionId,
        Map<String, Object> inputs,
        Object sourceEvent
) {
    public BedrockFormClick {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    public Object input(String key) {
        if (key == null) {
            return null;
        }
        Object value = inputs.get(key);
        return value == null ? inputs.get(key.toLowerCase(Locale.ROOT)) : value;
    }

    public String inputString(String key, String fallback) {
        Object value = input(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public boolean inputBoolean(String key, boolean fallback) {
        Object value = input(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return fallback;
        }
        return text.equalsIgnoreCase("true") || text.equalsIgnoreCase("yes")
                || text.equalsIgnoreCase("on") || text.equalsIgnoreCase("1");
    }

    public int inputInt(String key, int fallback) {
        Object value = input(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public float inputFloat(String key, float fallback) {
        Object value = input(key);
        if (value instanceof Number number) {
            return number.floatValue();
        }
        try {
            return Float.parseFloat(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T context(String key, Class<T> type, T fallback) {
        Object value = session == null ? null : session.context(key);
        return type != null && type.isInstance(value) ? (T) value : fallback;
    }
}
