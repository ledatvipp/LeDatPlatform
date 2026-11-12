package vn.ledat.platform.api.dialog;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DialogSession {
    private final UUID playerId;
    private final String pluginName;
    private final String dialogId;
    private final String sessionId;
    private final boolean nativeDialog;
    private final Instant createdAt;
    private final Map<String, Object> context = new ConcurrentHashMap<>();

    public DialogSession(UUID playerId, String pluginName, String dialogId, String sessionId, boolean nativeDialog, Map<String, Object> context) {
        this.playerId = playerId;
        this.pluginName = pluginName == null ? "unknown" : pluginName;
        this.dialogId = dialogId == null ? "default" : dialogId;
        this.sessionId = sessionId == null ? UUID.randomUUID().toString() : sessionId;
        this.nativeDialog = nativeDialog;
        this.createdAt = Instant.now();
        if (context != null) {
            this.context.putAll(context);
        }
    }

    public UUID playerId() { return playerId; }
    public String pluginName() { return pluginName; }
    public String dialogId() { return dialogId; }
    public String sessionId() { return sessionId; }
    public boolean nativeDialog() { return nativeDialog; }
    public Instant createdAt() { return createdAt; }
    public Map<String, Object> context() { return Map.copyOf(context); }
    public Object context(String key) { return context.get(key); }
    public void put(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (value == null) {
            context.remove(key);
        } else {
            context.put(key, value);
        }
    }
}
