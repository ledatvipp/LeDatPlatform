package vn.ledat.platform.api.gui;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GuiSession {
    private final UUID sessionId;
    private final UUID playerId;
    private final String pluginName;
    private final String menuId;
    private final int page;
    private final Instant openedAt;
    private final Map<String, Object> context = new ConcurrentHashMap<>();

    public GuiSession(UUID playerId, String pluginName, String menuId, int page) {
        this(UUID.randomUUID(), playerId, pluginName, menuId, page);
    }

    public GuiSession(UUID sessionId, UUID playerId, String pluginName, String menuId, int page) {
        this.sessionId = sessionId == null ? UUID.randomUUID() : sessionId;
        this.playerId = playerId;
        this.pluginName = pluginName;
        this.menuId = menuId;
        this.page = page;
        this.openedAt = Instant.now();
    }

    public UUID sessionId() { return sessionId; }
    public UUID playerId() { return playerId; }
    public String pluginName() { return pluginName; }
    public String menuId() { return menuId; }
    public int page() { return page; }
    public Instant openedAt() { return openedAt; }
    public Map<String, Object> context() { return context; }

    public boolean belongsTo(String pluginName, String menuId) {
        return this.pluginName.equalsIgnoreCase(pluginName == null ? "" : pluginName)
                && this.menuId.equalsIgnoreCase(menuId == null ? "" : menuId);
    }

    public boolean sameIdentity(GuiSession other) {
        return other != null && sessionId.equals(other.sessionId);
    }

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

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = key == null ? null : context.get(key);
        if (value == null || type == null || !type.isInstance(value)) {
            return Optional.empty();
        }
        return Optional.of((T) value);
    }
}
