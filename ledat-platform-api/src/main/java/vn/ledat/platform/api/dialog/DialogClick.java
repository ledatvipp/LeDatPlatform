package vn.ledat.platform.api.dialog;

import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;

public record DialogClick(
        Player player,
        DialogSession session,
        String actionId,
        boolean nativeDialog,
        Object sourceEvent,
        Map<String, String> inputs
) {
    public DialogClick(Player player, DialogSession session, String actionId, boolean nativeDialog, Object sourceEvent) {
        this(player, session, actionId, nativeDialog, sourceEvent, Map.of());
    }
    public String input(String key) {
        if (key == null || inputs == null) {
            return "";
        }
        return inputs.getOrDefault(key.toLowerCase(Locale.ROOT), inputs.getOrDefault(key, ""));
    }

    public boolean inputBoolean(String key, boolean fallback) {
        String value = input(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("on") || value.equalsIgnoreCase("1");
    }

    public float inputFloat(String key, float fallback) {
        try {
            return Float.parseFloat(input(key));
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
