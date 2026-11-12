package vn.ledat.platform.core.bedrockform;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.bedrockform.BedrockForm;
import vn.ledat.platform.api.bedrockform.BedrockFormButton;
import vn.ledat.platform.api.bedrockform.BedrockFormClick;
import vn.ledat.platform.api.bedrockform.BedrockFormClose;
import vn.ledat.platform.api.bedrockform.BedrockFormDisplayMode;
import vn.ledat.platform.api.bedrockform.BedrockFormInput;
import vn.ledat.platform.api.bedrockform.BedrockFormInputType;
import vn.ledat.platform.api.bedrockform.BedrockFormResult;
import vn.ledat.platform.api.bedrockform.BedrockFormService;
import vn.ledat.platform.api.bedrockform.BedrockFormSession;
import vn.ledat.platform.api.bedrockform.BedrockFormStats;
import vn.ledat.platform.api.language.LanguageService;
import vn.ledat.platform.api.scheduler.LeDatScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class DefaultBedrockFormService implements BedrockFormService, Listener {
    private final Plugin platformPlugin;
    private final LanguageService languages;
    private final LeDatScheduler scheduler;
    private final BedrockFormRenderer renderer;
    private final BedrockFormSessionStore sessions = new BedrockFormSessionStore();
    private volatile BedrockFormBridge bridge;

    private final AtomicLong opened = new AtomicLong();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong closed = new AtomicLong();
    private final AtomicLong expired = new AtomicLong();
    private final AtomicLong failedOpens = new AtomicLong();
    private final AtomicLong invalidResponses = new AtomicLong();
    private final AtomicLong blockedRapidSubmits = new AtomicLong();
    private final AtomicLong bedrockPlayersDetected = new AtomicLong();
    private final AtomicLong fallbacksToDialog = new AtomicLong();
    private final AtomicLong lastPressureWarnAt = new AtomicLong();

    public DefaultBedrockFormService(Plugin platformPlugin, LanguageService languages, LeDatScheduler scheduler) {
        this.platformPlugin = platformPlugin;
        this.languages = languages;
        this.scheduler = scheduler;
        this.renderer = new BedrockFormRenderer(platformPlugin);
        this.bridge = FloodgateCumulusBridge.create(platformPlugin);
        Bukkit.getPluginManager().registerEvents(this, platformPlugin);
    }

    public void reload() {
        this.bridge = FloodgateCumulusBridge.create(platformPlugin);
    }

    public void recordFallbackToDialog() {
        fallbacksToDialog.incrementAndGet();
    }

    @Override
    public boolean isAvailable() {
        return platformPlugin.getConfig().getBoolean("forms.enabled", true) && bridge.available();
    }

    @Override
    public boolean isBedrock(Player player) {
        if (player == null) {
            return false;
        }
        boolean bedrock = bridge.isBedrock(player.getUniqueId());
        if (bedrock) {
            bedrockPlayersDetected.incrementAndGet();
        }
        return bedrock;
    }

    @Override
    public boolean canOpen(Player player) {
        return isAvailable() && player != null && isBedrock(player);
    }

    @Override
    public SimpleBuilder simple(Plugin plugin, String menuId) {
        return new SimpleBedrockForm(this, owner(plugin), menuId);
    }

    @Override
    public ModalBuilder modal(Plugin plugin, String menuId) {
        return new ModalBedrockForm(this, owner(plugin), menuId);
    }

    @Override
    public CustomBuilder custom(Plugin plugin, String menuId) {
        return new CustomBedrockForm(this, owner(plugin), menuId);
    }

    @Override
    public BedrockFormResult open(Player player, BedrockForm form) {
        if (player == null || form == null) {
            return BedrockFormResult.INVALID_PLAYER;
        }
        Plugin owner = owner(form.plugin());
        if (!platformPlugin.getConfig().getBoolean("forms.enabled", true)) {
            sendLater(player, "forms.unavailable", Map.of("reason", "disabled"));
            return BedrockFormResult.DISABLED;
        }
        if (!ownerEnabled(owner)) {
            return BedrockFormResult.OWNER_DISABLED;
        }
        if (!bridge.available()) {
            failedOpens.incrementAndGet();
            sendLater(player, "forms.floodgate-missing", Map.of("reason", bridge.status()));
            return BedrockFormResult.UNAVAILABLE;
        }
        if (!isBedrock(player)) {
            sendLater(player, "forms.not-bedrock", Map.of());
            return BedrockFormResult.NOT_BEDROCK;
        }
        scheduler.entity(owner, player, () -> openOnEntity(player, form));
        return BedrockFormResult.OPENED;
    }

    private void openOnEntity(Player player, BedrockForm form) {
        Plugin owner = owner(form.plugin());
        if (!ownerEnabled(owner) || !player.isOnline()) {
            failedOpens.incrementAndGet();
            return;
        }
        RenderedBedrockForm rendered;
        try {
            if (form.displayMode() == BedrockFormDisplayMode.CUSTOM) validateCustomDefinition(form);
            rendered = renderer.render(form);
        } catch (IllegalArgumentException invalid) {
            failedOpens.incrementAndGet();
            languages.send(player, platformPlugin, "forms.invalid", Map.of());
            return;
        }
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = Instant.now().plus(ttl());
        BedrockFormSessionStore.StoredSession stored = sessions.put(player.getUniqueId(), form, rendered, sessionId, expiresAt);
        boolean sent = bridge.send(player, rendered, new SessionResponseHandler(player, stored.session()));
        if (!sent) {
            sessions.remove(player.getUniqueId(), stored);
            failedOpens.incrementAndGet();
            languages.send(player, platformPlugin, "forms.error", Map.of("menu", form.menuId()));
            return;
        }
        opened.incrementAndGet();
        warnPressure();
    }

    @Override
    public void close(Player player, boolean clearSession) {
        if (player == null) {
            return;
        }
        BedrockFormSessionStore.StoredSession expected = sessions.get(player.getUniqueId());
        scheduler.entity(platformPlugin, player, () -> {
            if (expected != null && sessions.get(player.getUniqueId()) == expected) {
                BedrockFormSessionStore.StoredSession removed = clearSession ? sessions.take(player.getUniqueId(), expected.session().sessionId()) : expected;
                safeClose(player, removed, "platform-close", null);
            }
        });
    }

    @Override
    public BedrockFormSession session(UUID playerId) {
        BedrockFormSessionStore.StoredSession stored = sessions.get(playerId);
        return stored == null ? null : stored.session();
    }

    @Override public int sessionCount() { return sessions.size(); }

    @Override
    public int sessionCount(Plugin plugin) {
        return sessions.countPlugin(plugin == null ? null : plugin.getName());
    }

    @Override
    public void cleanup(UUID playerId) {
        sessions.remove(playerId);
    }

    @Override
    public void cleanup(Plugin plugin) {
        if (plugin != null) {
            sessions.removePlugin(plugin.getName());
        }
    }

    @Override
    public void cleanupAll() {
        sessions.clear();
    }

    @Override
    public int cleanupExpired() {
        List<BedrockFormSessionStore.StoredSession> removed = sessions.removeExpired(Instant.now());
        if (!removed.isEmpty()) {
            expired.addAndGet(removed.size());
        }
        return removed.size();
    }

    @Override
    public BedrockFormStats stats() {
        return new BedrockFormStats(
                sessions.size(),
                opened.get(),
                submitted.get(),
                closed.get(),
                expired.get(),
                failedOpens.get(),
                invalidResponses.get(),
                blockedRapidSubmits.get(),
                bedrockPlayersDetected.get(),
                fallbacksToDialog.get()
        );
    }

    @Override
    public Map<String, String> diagnostics(Player player) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("enabled", String.valueOf(platformPlugin.getConfig().getBoolean("forms.enabled", true)));
        out.put("floodgate-plugin", String.valueOf(bridge.floodgatePluginDetected()));
        out.put("geyser-plugin", String.valueOf(bridge.geyserPluginDetected()));
        out.put("floodgate-class", String.valueOf(bridge.floodgateClassDetected()));
        out.put("geyser-api-class", String.valueOf(bridge.geyserApiClassDetected()));
        out.put("cumulus-class", String.valueOf(bridge.cumulusClassDetected()));
        out.put("bridge", String.valueOf(bridge.available()));
        out.put("status", bridge.status());
        out.put("sessions", String.valueOf(sessionCount()));
        out.put("bedrock", String.valueOf(player != null && isBedrock(player)));
        out.put("dialog-routing", String.valueOf(platformPlugin.getConfig().getBoolean("forms.dialog-routing.enabled", true)));
        out.put("prefer-for-bedrock", String.valueOf(platformPlugin.getConfig().getBoolean("forms.prefer-for-bedrock", true)));
        BedrockFormRenderer.ProjectionStats projection = renderer.projectionStats();
        out.put("text-object-fallbacks", String.valueOf(projection.objectFallbacks()));
        out.put("text-blank-id-fallbacks", String.valueOf(projection.blankIdFallbacks()));
        out.put("text-projection-failures", String.valueOf(projection.failures()));
        return out;
    }

    public String bridgeStatus() {
        return bridge.status();
    }

    public boolean floodgatePluginDetected() {
        return bridge.floodgatePluginDetected();
    }

    public boolean floodgateClassDetected() {
        return bridge.floodgateClassDetected();
    }

    public boolean geyserApiClassDetected() {
        return bridge.geyserApiClassDetected();
    }

    public boolean bridgeAvailable() {
        return bridge.available();
    }

    private void handleValid(Player player, BedrockFormSession expected, int clickedButtonIndex, Map<String, Object> inputs, Object source) {
        UUID playerId = expected.playerId();
        BedrockFormSessionStore.StoredSession stored = sessions.get(playerId);
        if (!player.getUniqueId().equals(playerId) || !sameSession(stored, expected)) {
            reject("session-mismatch", stored);
            return;
        }
        Map<String, Object> snapshot;
        try {
            snapshot = Map.copyOf(inputs);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            handleInvalid(player, expected, source);
            return;
        }
        Plugin owner = owner(stored.form().plugin());
        scheduler.entity(owner, player, () -> claimAndRun(player, expected, clickedButtonIndex, snapshot, source));
    }

    private void claimAndRun(Player player, BedrockFormSession expected, int clickedButtonIndex, Map<String, Object> inputs, Object source) {
        UUID playerId = expected.playerId();
        BedrockFormSessionStore.StoredSession stored = sessions.get(playerId);
        if (!sameSession(stored, expected) || !player.isOnline() || !ownerEnabled(owner(stored.form().plugin()))) {
            reject("session-no-longer-active", stored);
            return;
        }
        if (stored.session().expired()) {
            BedrockFormSessionStore.StoredSession expiredSession = sessions.take(playerId, expected.sessionId());
            if (expiredSession != null) {
                expired.incrementAndGet();
                reject("expired", expiredSession);
                sendLater(player, "forms.expired", Map.of("menu", expiredSession.session().menuId()));
            } else {
                reject("session-mismatch", sessions.get(playerId));
            }
            return;
        }
        String actionId = actionId(stored.rendered(), clickedButtonIndex);
        if (actionId == null) {
            BedrockFormSessionStore.StoredSession rejected = sessions.take(playerId, expected.sessionId());
            reject("unknown-button", rejected == null ? sessions.get(playerId) : rejected);
            return;
        }
        Map<String, Object> normalized;
        try {
            normalized = stored.form().displayMode() == BedrockFormDisplayMode.CUSTOM
                    ? normalizeCustomInputs(stored.form(), inputs) : inputs;
        } catch (IllegalArgumentException invalid) {
            BedrockFormSessionStore.StoredSession rejected = sessions.take(playerId, expected.sessionId());
            reject("invalid-inputs", rejected);
            languages.send(player, platformPlugin, "forms.invalid", Map.of());
            return;
        }
        BedrockFormSessionStore.StoredSession claimed = sessions.take(playerId, expected.sessionId());
        if (claimed == null) {
            reject("already-consumed", sessions.get(playerId));
            return;
        }
        runAction(player, claimed, actionId, normalized, source);
    }

    private static java.util.Set<String> validateCustomDefinition(BedrockForm form) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (BedrockFormInput input : form.inputs()) {
            if (input.type() == BedrockFormInputType.LABEL) continue;
            if (!keys.add(input.key())) throw new IllegalArgumentException("Duplicate input key: " + input.key());
            if (input.type() == BedrockFormInputType.DROPDOWN || input.type() == BedrockFormInputType.STEP_SLIDER) {
                if (input.options().isEmpty() || !keys.add(input.key() + ".index")) throw new IllegalArgumentException("Invalid choice definition: " + input.key());
            }
            if (input.type() == BedrockFormInputType.SLIDER
                    && (!Float.isFinite(input.min()) || !Float.isFinite(input.max()) || !Float.isFinite(input.step())
                    || input.step() <= 0f || !Float.isFinite(input.initialNumber())
                    || input.initialNumber() < input.min() || input.initialNumber() > input.max())) {
                throw new IllegalArgumentException("Invalid slider definition: " + input.key());
            }
        }
        return keys;
    }

    static Map<String, Object> normalizeCustomInputs(BedrockForm form, Map<String, Object> inputs) {
        java.util.Set<String> keys = validateCustomDefinition(form);
        if (!keys.equals(inputs.keySet())) throw new IllegalArgumentException("Missing or unknown form input");
        Map<String, Object> result = new LinkedHashMap<>();
        for (BedrockFormInput input : form.inputs()) {
            if (input.type() == BedrockFormInputType.LABEL) continue;
            Object value = inputs.get(input.key());
            switch (input.type()) {
                case TEXT -> {
                    if (!(value instanceof String)) throw new IllegalArgumentException("Invalid text input: " + input.key());
                    result.put(input.key(), value);
                }
                case BOOLEAN -> {
                    if (!(value instanceof Boolean)) throw new IllegalArgumentException("Invalid boolean input: " + input.key());
                    result.put(input.key(), value);
                }
                case SLIDER -> {
                    if (!(value instanceof Number number) || !Float.isFinite(number.floatValue())) throw new IllegalArgumentException("Invalid slider input: " + input.key());
                    float selected = number.floatValue();
                    double steps = (selected - (double) input.min()) / input.step();
                    double nearest = input.min() + Math.rint(steps) * input.step();
                    double tolerance = Math.max(Math.abs((double) input.step()) * 0.0001D, Math.max(Math.ulp(selected), Math.ulp(input.min())) * 2D);
                    if (selected < input.min() || selected > input.max()
                            || (selected != input.max() && Math.abs(selected - nearest) > tolerance)) {
                        throw new IllegalArgumentException("Slider outside bounds/step: " + input.key());
                    }
                    result.put(input.key(), value);
                }
                case DROPDOWN, STEP_SLIDER -> {
                    Object rawIndex = inputs.get(input.key() + ".index");
                    if (!(value instanceof String) || !(rawIndex instanceof Number number) || !Double.isFinite(number.doubleValue())
                            || number.doubleValue() != number.intValue() || number.intValue() < 0 || number.intValue() >= input.options().size()) {
                        throw new IllegalArgumentException("Invalid choice input: " + input.key());
                    }
                    int index = number.intValue();
                    result.put(input.key() + ".index", index);
                    result.put(input.key(), input.options().get(index));
                }
                case LABEL -> { }
            }
        }
        return Map.copyOf(result);
    }

    private void runAction(Player player, BedrockFormSessionStore.StoredSession stored, String actionId, Map<String, Object> inputs, Object source) {
        Plugin owner = owner(stored.form().plugin());
        if (!player.isOnline() || !ownerEnabled(owner)) {
            return;
        }
        BedrockFormButton button = stored.form().buttons().stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(actionId))
                .findFirst()
                .orElse(null);
        if (button == null) {
            reject("missing-action", stored);
            return;
        }
        try {
            submitted.incrementAndGet();
            button.action().accept(new BedrockFormClick(player, stored.session(), button.id(), inputs, source));
        } catch (Throwable error) {
            owner.getLogger().log(Level.WARNING, "Bedrock form action failed: " + stored.session().menuId() + "/" + button.id(), error);
            languages.send(player, platformPlugin, "forms.error", Map.of("menu", stored.session().menuId()));
        }
    }

    private void handleClosed(Player player, BedrockFormSession expected, Object source) {
        BedrockFormSessionStore.StoredSession stored = sessions.get(expected.playerId());
        if (!player.getUniqueId().equals(expected.playerId()) || !sameSession(stored, expected)) {
            reject("close-session-mismatch", sessions.get(expected.playerId()));
            return;
        }
        Plugin owner = owner(stored.form().plugin());
        scheduler.entity(owner, player, () -> {
            if (!player.isOnline() || !ownerEnabled(owner)) return;
            BedrockFormSessionStore.StoredSession claimed = sessions.take(expected.playerId(), expected.sessionId());
            if (claimed == null) return;
            closed.incrementAndGet();
            safeClose(player, claimed, "closed", source);
        });
    }

    private void handleInvalid(Player player, BedrockFormSession expected, Object source) {
        if (!player.getUniqueId().equals(expected.playerId())) {
            reject("invalid-player-mismatch", sessions.get(expected.playerId()));
            return;
        }
        BedrockFormSessionStore.StoredSession stored = sessions.take(expected.playerId(), expected.sessionId());
        if (stored == null) {
            reject("invalid-session-mismatch", sessions.get(expected.playerId()));
            return;
        }
        reject("invalid-response", stored);
        sendLater(player, "forms.invalid", Map.of());
    }

    private void safeClose(Player player, BedrockFormSessionStore.StoredSession stored, String reason, Object source) {
        if (stored == null || player == null || !player.isOnline()) {
            return;
        }
        Plugin owner = owner(stored.form().plugin());
        if (!ownerEnabled(owner)) {
            return;
        }
        try {
            stored.form().closeHandler().accept(new BedrockFormClose(player, stored.session(), reason, source));
        } catch (Throwable error) {
            owner.getLogger().log(Level.WARNING, "Bedrock form close handler failed: " + stored.session().menuId(), error);
        }
    }

    private boolean sameSession(BedrockFormSessionStore.StoredSession stored, BedrockFormSession expected) {
        return stored != null
                && expected != null
                && stored.session().playerId().equals(expected.playerId())
                && stored.session().sessionId().equals(expected.sessionId())
                && stored.session().pluginName().equalsIgnoreCase(expected.pluginName())
                && stored.session().menuId().equalsIgnoreCase(expected.menuId());
    }

    private String actionId(RenderedBedrockForm rendered, int clickedButtonIndex) {
        if (rendered.mode() == BedrockFormDisplayMode.CUSTOM) {
            return rendered.buttons().isEmpty() ? null : rendered.buttons().get(0).id();
        }
        if (clickedButtonIndex < 0 || clickedButtonIndex >= rendered.buttons().size()) {
            return null;
        }
        return rendered.buttons().get(clickedButtonIndex).id();
    }

    private void reject(String reason, BedrockFormSessionStore.StoredSession stored) {
        invalidResponses.incrementAndGet();
        if (!platformPlugin.getConfig().getBoolean("forms.security.log-invalid-response", true)) {
            return;
        }
        if (stored == null) {
            platformPlugin.getLogger().fine("Rejected Bedrock form response: " + reason + " session=missing");
            return;
        }
        platformPlugin.getLogger().fine("Rejected Bedrock form response: " + reason
                + " plugin=" + stored.session().pluginName()
                + " menu=" + stored.session().menuId()
                + " session=" + stored.session().sessionId());
    }

    private void sendLater(Player player, String key, Map<String, String> placeholders) {
        if (player == null) {
            return;
        }
        scheduler.entity(platformPlugin, player, () -> languages.send(player, platformPlugin, key, placeholders));
    }

    private void warnPressure() {
        int warnSessions = Math.max(1, platformPlugin.getConfig().getInt("forms.diagnostics.warn-active-sessions", 200));
        if (sessions.size() > warnSessions) {
            // Above the threshold every open would log; throttle so a busy hour
            // does not flood the console right when the server is under load.
            long now = System.currentTimeMillis();
            long previous = lastPressureWarnAt.get();
            if (now - previous >= 30_000L && lastPressureWarnAt.compareAndSet(previous, now)) {
                platformPlugin.getLogger().warning("Active Bedrock form sessions high: " + sessions.size());
            }
        }
    }

    private Duration ttl() {
        return Duration.ofSeconds(Math.max(5L, platformPlugin.getConfig().getLong("forms.session-ttl-seconds", 180L)));
    }

    private Plugin owner(Plugin owner) {
        return owner == null ? platformPlugin : owner;
    }

    private boolean ownerEnabled(Plugin owner) {
        try {
            return owner != null && owner.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        sessions.remove(playerId);
    }

    private final class SessionResponseHandler implements BedrockFormBridge.BridgeResponseHandler {
        private final Player player;
        private final BedrockFormSession session;

        private SessionResponseHandler(Player player, BedrockFormSession session) {
            this.player = player;
            this.session = session;
        }

        @Override
        public void valid(int clickedButtonIndex, Map<String, Object> inputs, Object source) {
            handleValid(player, session, clickedButtonIndex, inputs == null ? Map.of() : inputs, source);
        }

        @Override
        public void closed(Object source) {
            handleClosed(player, session, source);
        }

        @Override
        public void invalid(Object source) {
            handleInvalid(player, session, source);
        }
    }
}
