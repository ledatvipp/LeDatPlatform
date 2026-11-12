package vn.ledat.platform.api.bedrockform;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BedrockFormSession {
    private final UUID playerId;
    private final String pluginName;
    private final String menuId;
    private final String sessionId;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Map<String, Object> context = new ConcurrentHashMap<>();

    public BedrockFormSession(UUID playerId, String pluginName, String menuId, String sessionId,
                              Instant createdAt, Instant expiresAt, Map<String, Object> context) {
        this.playerId = playerId;
        this.pluginName = pluginName == null ? "unknown" : pluginName;
        this.menuId = menuId == null ? "default" : menuId;
        this.sessionId = sessionId == null ? UUID.randomUUID().toString() : sessionId;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.expiresAt = expiresAt == null ? this.createdAt.plusSeconds(180L) : expiresAt;
        if (context != null) {
            this.context.putAll(context);
        }
    }

    public UUID playerId() { return playerId; }
    public String pluginName() { return pluginName; }
    public String menuId() { return menuId; }
    public String sessionId() { return sessionId; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public boolean expired() { return Instant.now().isAfter(expiresAt); }
    public Map<String, Object> context() { return Map.copyOf(context); }
    public Object context(String key) { return key == null ? null : context.get(key); }

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
