package vn.ledat.platform.common.pvp;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-local PvP reward pipeline for shaded LeDat plugins.
 *
 * <p>Because each migrated plugin shades/relocates ledat-platform-common, this
 * bridge stores only Bukkit/JDK types in {@link System#getProperties()}. Guard
 * providers expose a public method with signature:</p>
 *
 * <pre>public String guardPvpReward(Player killer, Player victim, String source)</pre>
 *
 * <p>Return {@code null}, blank or "ALLOW" to allow. Return any other string to
 * block rewards for that kill. Reward plugins call {@link #runGuards(JavaPlugin,
 * Player, Player, String)} before applying Elo/Shards/Bounty.</p>
 */
public final class LeDatPvpRewardPipeline {
    private static final String GUARDS_KEY = "ledat.suite.pvp.reward.guards.v1";
    private static final String AUTHORITIES_KEY = "ledat.suite.pvp.reward.authorities.v1";

    private LeDatPvpRewardPipeline() {
    }

    public static void registerGuard(JavaPlugin plugin, String guardId, String methodName, int priority) {
        if (plugin == null || guardId == null || guardId.isBlank() || methodName == null || methodName.isBlank()) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("plugin", plugin);
        entry.put("pluginName", plugin.getName());
        entry.put("guardId", normalize(guardId));
        entry.put("methodName", methodName.trim());
        entry.put("priority", priority);
        entry.put("registeredAtMillis", System.currentTimeMillis());
        guards().put(plugin.getName() + ':' + normalize(guardId), entry);
    }

    public static void unregisterGuard(JavaPlugin plugin, String guardId) {
        if (plugin == null) {
            return;
        }
        if (guardId == null || guardId.isBlank()) {
            guards().entrySet().removeIf(entry -> entry.getKey().startsWith(plugin.getName() + ':'));
            return;
        }
        guards().remove(plugin.getName() + ':' + normalize(guardId));
    }

    public static void registerAuthority(JavaPlugin plugin, String authorityId, int priority) {
        if (plugin == null || authorityId == null || authorityId.isBlank()) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("pluginName", plugin.getName());
        entry.put("authorityId", normalize(authorityId));
        entry.put("priority", priority);
        entry.put("lastSeenMillis", System.currentTimeMillis());
        authorities().put(plugin.getName() + ':' + normalize(authorityId), entry);
    }

    public static void unregisterAuthority(JavaPlugin plugin, String authorityId) {
        if (plugin == null) {
            return;
        }
        if (authorityId == null || authorityId.isBlank()) {
            authorities().entrySet().removeIf(entry -> entry.getKey().startsWith(plugin.getName() + ':'));
            return;
        }
        authorities().remove(plugin.getName() + ':' + normalize(authorityId));
    }

    public static String currentAuthority() {
        return authorities().values().stream()
                .sorted(Comparator.comparingInt(LeDatPvpRewardPipeline::priority).reversed())
                .map(entry -> String.valueOf(entry.get("pluginName")) + '/' + String.valueOf(entry.get("authorityId")))
                .findFirst()
                .orElse("none");
    }

    public static GuardDecision runGuards(JavaPlugin caller, Player killer, Player victim, String source) {
        if (caller != null && !caller.getConfig().getBoolean("pvp-reward-pipeline.enabled", true)) {
            return GuardDecision.allow();
        }
        if (killer == null || victim == null) {
            return GuardDecision.blocked("invalid-context", "invalid");
        }
        List<Map<String, Object>> ordered = new ArrayList<>(guards().values());
        ordered.sort(Comparator.comparingInt(LeDatPvpRewardPipeline::priority).reversed());
        for (Map<String, Object> entry : ordered) {
            Object pluginObject = entry.get("plugin");
            if (!(pluginObject instanceof JavaPlugin guardPlugin) || !guardPlugin.isEnabled()) {
                continue;
            }
            String methodName = String.valueOf(entry.get("methodName"));
            String guardId = String.valueOf(entry.getOrDefault("guardId", guardPlugin.getName()));
            try {
                Method method = guardPlugin.getClass().getMethod(methodName, Player.class, Player.class, String.class);
                Object result = method.invoke(guardPlugin, killer, victim, source == null ? "unknown" : source);
                GuardDecision decision = parseResult(guardId, result);
                if (!decision.allowed()) {
                    return decision;
                }
            } catch (NoSuchMethodException error) {
                warn(caller, guardPlugin.getName() + " guard method " + methodName + " was not found.");
            } catch (Throwable error) {
                warn(caller, guardPlugin.getName() + " guard failed: " + error.getMessage());
            }
        }
        return GuardDecision.allow();
    }

    public static List<Map<String, String>> snapshotGuards() {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, Object> entry : guards().values()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("plugin", String.valueOf(entry.get("pluginName")));
            row.put("guard", String.valueOf(entry.get("guardId")));
            row.put("method", String.valueOf(entry.get("methodName")));
            row.put("priority", String.valueOf(entry.get("priority")));
            out.add(row);
        }
        out.sort(Comparator.comparing(row -> row.getOrDefault("plugin", "")));
        return List.copyOf(out);
    }


    public static List<Map<String, String>> snapshotAuthorities() {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, Object> entry : authorities().values()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("plugin", String.valueOf(entry.get("pluginName")));
            row.put("authority", String.valueOf(entry.get("authorityId")));
            row.put("priority", String.valueOf(entry.get("priority")));
            row.put("lastSeenMillis", String.valueOf(entry.get("lastSeenMillis")));
            out.add(row);
        }
        out.sort(Comparator.comparing(row -> row.getOrDefault("plugin", "")));
        return List.copyOf(out);
    }

    private static GuardDecision parseResult(String guardId, Object result) {
        if (result == null) {
            return GuardDecision.allow();
        }
        if (result instanceof Boolean bool) {
            return bool ? GuardDecision.allow() : GuardDecision.blocked(guardId, "blocked");
        }
        if (result instanceof Map<?, ?> map) {
            Object allowed = map.get("allowed");
            if (allowed instanceof Boolean bool && bool) {
                return GuardDecision.allow();
            }
            Object reason = map.get("reason");
            return GuardDecision.blocked(guardId, reason == null ? "blocked" : String.valueOf(reason));
        }
        String text = String.valueOf(result).trim();
        if (text.isEmpty() || text.equalsIgnoreCase("ALLOW") || text.equalsIgnoreCase("ALLOWED")) {
            return GuardDecision.allow();
        }
        return GuardDecision.blocked(guardId, text);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, Map<String, Object>> guards() {
        synchronized (System.getProperties()) {
            Object existing = System.getProperties().get(GUARDS_KEY);
            if (existing instanceof ConcurrentHashMap<?, ?> map) {
                return (ConcurrentHashMap<String, Map<String, Object>>) map;
            }
            ConcurrentHashMap<String, Map<String, Object>> created = new ConcurrentHashMap<>();
            System.getProperties().put(GUARDS_KEY, created);
            return created;
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, Map<String, Object>> authorities() {
        synchronized (System.getProperties()) {
            Object existing = System.getProperties().get(AUTHORITIES_KEY);
            if (existing instanceof ConcurrentHashMap<?, ?> map) {
                return (ConcurrentHashMap<String, Map<String, Object>>) map;
            }
            ConcurrentHashMap<String, Map<String, Object>> created = new ConcurrentHashMap<>();
            System.getProperties().put(AUTHORITIES_KEY, created);
            return created;
        }
    }

    private static int priority(Map<String, Object> entry) {
        Object value = entry.get("priority");
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String normalize(String raw) {
        return Objects.toString(raw, "").trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static void warn(JavaPlugin caller, String message) {
        if (caller != null) {
            caller.getLogger().warning("[PvPRewardPipeline] " + message);
        }
    }

    public record GuardDecision(boolean allowed, String guardId, String reason) {
        public static GuardDecision allow() {
            return new GuardDecision(true, "none", "allow");
        }

        public static GuardDecision blocked(String guardId, String reason) {
            return new GuardDecision(false, guardId == null ? "unknown" : guardId, reason == null ? "blocked" : reason);
        }
    }
}
