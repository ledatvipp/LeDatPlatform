package vn.ledat.platform.core.bedrockform;

import vn.ledat.platform.api.bedrockform.BedrockForm;
import vn.ledat.platform.api.bedrockform.BedrockFormSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class BedrockFormSessionStore {
    private final Map<UUID, StoredSession> sessions = new ConcurrentHashMap<>();

    public StoredSession put(UUID playerId, BedrockForm form, RenderedBedrockForm rendered, String sessionId, Instant expiresAt) {
        BedrockFormSession session = new BedrockFormSession(
                playerId,
                form.plugin() == null ? "unknown" : form.plugin().getName(),
                form.menuId(),
                sessionId,
                Instant.now(),
                expiresAt,
                form.context()
        );
        StoredSession stored = new StoredSession(session, form, rendered);
        sessions.put(playerId, stored);
        return stored;
    }

    public StoredSession get(UUID playerId) {
        return playerId == null ? null : sessions.get(playerId);
    }

    public StoredSession remove(UUID playerId) {
        return playerId == null ? null : sessions.remove(playerId);
    }

    public boolean remove(UUID playerId, StoredSession expected) {
        return playerId != null && expected != null && sessions.remove(playerId, expected);
    }

    public StoredSession take(UUID playerId, String sessionId) {
        if (playerId == null || sessionId == null) {
            return null;
        }
        AtomicReference<StoredSession> taken = new AtomicReference<>();
        sessions.computeIfPresent(playerId, (ignored, current) -> {
            if (!sessionId.equals(current.session().sessionId()) || !current.consume()) {
                return current;
            }
            taken.set(current);
            return null;
        });
        return taken.get();
    }

    public int size() {
        return sessions.size();
    }

    public int countPlugin(String pluginName) {
        if (pluginName == null) {
            return size();
        }
        return (int) sessions.values().stream()
                .filter(session -> session.session().pluginName().equalsIgnoreCase(pluginName))
                .count();
    }

    public List<StoredSession> removePlugin(String pluginName) {
        if (pluginName == null) {
            return List.of();
        }
        List<StoredSession> removed = new ArrayList<>();
        sessions.entrySet().removeIf(entry -> {
            boolean match = entry.getValue().session().pluginName().equalsIgnoreCase(pluginName);
            if (match) {
                removed.add(entry.getValue());
            }
            return match;
        });
        return removed;
    }

    public List<StoredSession> removeExpired(Instant now) {
        Instant safeNow = now == null ? Instant.now() : now;
        List<StoredSession> removed = new ArrayList<>();
        sessions.entrySet().removeIf(entry -> {
            boolean expired = safeNow.isAfter(entry.getValue().session().expiresAt());
            if (expired) {
                removed.add(entry.getValue());
            }
            return expired;
        });
        return removed;
    }

    public List<StoredSession> clear() {
        List<StoredSession> removed = List.copyOf(sessions.values());
        sessions.clear();
        return removed;
    }

    public static final class StoredSession {
        private final BedrockFormSession session;
        private final BedrockForm form;
        private final RenderedBedrockForm rendered;
        private final AtomicBoolean consumed = new AtomicBoolean(false);

        private StoredSession(BedrockFormSession session, BedrockForm form, RenderedBedrockForm rendered) {
            this.session = session;
            this.form = form;
            this.rendered = rendered;
        }

        public BedrockFormSession session() { return session; }
        public BedrockForm form() { return form; }
        public RenderedBedrockForm rendered() { return rendered; }
        public boolean consumed() { return consumed.get(); }
        public boolean consume() { return consumed.compareAndSet(false, true); }
    }
}
