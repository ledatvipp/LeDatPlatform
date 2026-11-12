package vn.ledat.platform.core.lifecycle;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.LeDatPlatformApi;
import vn.ledat.platform.api.hook.HookStatus;
import vn.ledat.platform.core.integration.BuiltInIntegrations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Central owner lifecycle cleanup for services exposed by LeDatPlatform.
 *
 * <p>Child plugins can still cleanup their own runtime state normally. This service
 * is a second safety net so platform-owned registries do not retain command routes,
 * GUI/dialog sessions, scheduler tasks, local storage handles, placeholder routes,
 * managed executors, or plugin language bundles after a child plugin reload/disable.</p>
 */
public final class DefaultOwnerLifecycleService implements Listener {
    private final Plugin platformPlugin;
    private final Supplier<LeDatPlatformApi> apiSupplier;
    private final Set<String> cleaning = ConcurrentHashMap.newKeySet();
    private final Map<String, OwnerCleanupStats> recent = new ConcurrentHashMap<>();
    private final AtomicLong cleanupRuns = new AtomicLong();
    private final AtomicLong cleanupFailures = new AtomicLong();
    private final AtomicLong hookRefreshes = new AtomicLong();

    public DefaultOwnerLifecycleService(Plugin platformPlugin, Supplier<LeDatPlatformApi> apiSupplier) {
        this.platformPlugin = platformPlugin;
        this.apiSupplier = apiSupplier;
        Bukkit.getPluginManager().registerEvents(this, platformPlugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (!platformPlugin.getConfig().getBoolean("owner-lifecycle.enabled", true)
                || !platformPlugin.getConfig().getBoolean("owner-lifecycle.cleanup-on-plugin-disable", true)) {
            return;
        }
        cleanupOwner(event.getPlugin(), "plugin-disable");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (!platformPlugin.getConfig().getBoolean("owner-lifecycle.enabled", true)
                || !platformPlugin.getConfig().getBoolean("owner-lifecycle.refresh-hooks-on-plugin-enable", true)
                || event.getPlugin().equals(platformPlugin)) {
            return;
        }
        LeDatPlatformApi api = apiSupplier.get();
        if (api == null) {
            return;
        }
        try {
            api.hooks().reload();
            hookRefreshes.incrementAndGet();
            if (event.getPlugin().getName().equalsIgnoreCase("PlaceholderAPI")) {
                BuiltInIntegrations.installPlaceholderApi(platformPlugin, api);
            }
        } catch (Throwable error) {
            platformPlugin.getLogger().log(Level.FINE, "Could not refresh hook registry after plugin enable: " + event.getPlugin().getName(), error);
        }
    }

    public void cleanupOwner(Plugin owner, String reason) {
        if (owner == null || owner.equals(platformPlugin)) {
            return;
        }
        LeDatPlatformApi api = apiSupplier.get();
        if (api == null) {
            return;
        }
        String key = normalize(owner.getName());
        if (!cleaning.add(key)) {
            return;
        }
        long started = System.currentTimeMillis();
        int failures = 0;
        OwnerCleanupStats before = snapshot(owner, reason, 0L, 0);
        if (!before.hasState() && shouldSkipStatelessCleanup(owner, reason)) {
            cleaning.remove(key);
            return;
        }
        try {
            failures += runStep(owner, "forms", () -> api.forms().cleanup(owner));
            failures += runStep(owner, "dialogs", () -> api.dialogs().cleanup(owner));
            failures += runStep(owner, "guis", () -> api.guis().cleanup(owner));
            failures += runStep(owner, "sounds", () -> api.sounds().cleanup(owner));
            failures += runStep(owner, "commands", () -> api.commands().unregisterPlugin(owner));
            failures += runStep(owner, "placeholders", () -> api.placeholders().unregisterPlugin(owner));
            failures += runStep(owner, "languages", () -> api.languages().unregisterPlugin(owner));
            failures += runStep(owner, "hooks", () -> api.hooks().unregisterPlugin(owner));
            failures += runStep(owner, "items", () -> api.items().unregister(owner));
            failures += runStep(owner, "economy", () -> api.economy().unregister(owner));
            failures += runStep(owner, "user-cache-flush", () -> flushUserCachesAsync(api, owner));
            failures += runStep(owner, "cooldowns", () -> api.cooldowns().clearNamespace(owner.getName()));
            failures += runStep(owner, "rate-limits", () -> api.rateLimits().clearNamespace(owner.getName()));
            failures += runStep(owner, "scheduler", () -> api.scheduler().cancelAll(owner));
            failures += runStep(owner, "executors", () -> shutdownExecutorsAsync(api, owner));
            failures += runStep(owner, "storage", () -> closeStorageAsync(api, owner));
        } finally {
            cleanupRuns.incrementAndGet();
            if (failures > 0) {
                cleanupFailures.addAndGet(failures);
            }
            long took = System.currentTimeMillis() - started;
            OwnerCleanupStats stats = before.withResult(took, failures);
            remember(stats);
            cleaning.remove(key);
            if (platformPlugin.getConfig().getBoolean("owner-lifecycle.log-cleanup-summary", true)
                    && (stats.hasState() || platformPlugin.getConfig().getBoolean("owner-lifecycle.log-stateless-cleanup", false))) {
                platformPlugin.getLogger().info("Owner cleanup " + owner.getName()
                        + " reason=" + stats.reason()
                        + " commands=" + stats.commandRoutes()
                        + " placeholders=" + stats.placeholderRoutes()
                        + " gui=" + stats.guiSessions()
                        + " dialogs=" + stats.dialogSessions()
                        + " forms=" + stats.formSessions()
                        + " scheduler=" + stats.schedulerTasks()
                        + " executors=" + stats.executors()
                        + " storage=" + stats.storageUnits()
                        + " caches=" + stats.userCaches()
                        + " hooks=" + stats.hookEntries()
                        + " failures=" + stats.failures()
                        + " took=" + stats.tookMillis() + "ms");
            }
        }
    }

    public void cleanupAllLoaded(String reason) {
        LeDatPlatformApi api = apiSupplier.get();
        if (api == null) {
            return;
        }
        Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .filter(plugin -> !plugin.equals(platformPlugin))
                .filter(plugin -> ownsPlatformState(api, plugin) || plugin.getName().startsWith("LeDat"))
                .sorted(Comparator.comparing(Plugin::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(plugin -> cleanupOwner(plugin, reason == null || reason.isBlank() ? "cleanup-all" : reason));
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    public LifecycleStats stats() {
        return new LifecycleStats(
                platformPlugin.getConfig().getBoolean("owner-lifecycle.enabled", true),
                platformPlugin.getConfig().getBoolean("owner-lifecycle.cleanup-on-plugin-disable", true),
                platformPlugin.getConfig().getBoolean("owner-lifecycle.refresh-hooks-on-plugin-enable", true),
                cleanupRuns.get(),
                cleanupFailures.get(),
                hookRefreshes.get(),
                recent.size(),
                Instant.now()
        );
    }

    public List<OwnerCleanupStats> recentCleanups() {
        return recent.values().stream()
                .sorted(Comparator.comparingLong(OwnerCleanupStats::startedAtMillis).reversed())
                .toList();
    }

    private boolean ownsPlatformState(LeDatPlatformApi api, Plugin owner) {
        return snapshot(owner, "probe", 0L, 0).hasState();
    }

    private boolean shouldSkipStatelessCleanup(Plugin owner, String reason) {
        if (!platformPlugin.getConfig().getBoolean("owner-lifecycle.skip-stateless-plugin-disable", true)) {
            return false;
        }
        String safeReason = safeReason(reason);
        boolean shutdownCleanup = "plugin-disable".equals(safeReason)
                || "platform-disable".equals(safeReason)
                || "cleanup-all".equals(safeReason);
        return shutdownCleanup && !owner.getName().startsWith("LeDat");
    }

    private OwnerCleanupStats snapshot(Plugin owner, String reason, long tookMillis, int failures) {
        String pluginName = owner.getName();
        String normalized = normalize(pluginName);
        LeDatPlatformApi api = apiSupplier.get();
        if (api == null) {
            return new OwnerCleanupStats(pluginName, safeReason(reason), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, System.currentTimeMillis(), tookMillis, failures);
        }
        int commandRoutes = (int) api.commands().routes().values().stream()
                .filter(route -> route.pluginName().equalsIgnoreCase(pluginName))
                .count();
        int placeholderRoutes = (int) api.placeholders().routes().stream()
                .filter(route -> route.pluginName().equalsIgnoreCase(pluginName))
                .count();
        int guiSessions = api.guis().sessionCount(owner);
        int dialogSessions = api.dialogs().sessionCount(owner);
        int formSessions = api.forms().sessionCount(owner);
        int schedulerTasks = api.scheduler().trackedTaskCount(owner);
        int storageUnits = (int) api.database().storageUnits().values().stream()
                .filter(unit -> normalize(unit.pluginId()).equals(normalized))
                .count();
        int executors = (int) api.executors().stats().values().stream()
                .filter(stats -> stats.pluginName().equalsIgnoreCase(pluginName))
                .count();
        int hooks = (int) api.hooks().statuses().stream()
                .filter(status -> ownerMatches(status, pluginName))
                .count();
        String cachePrefix = pluginName.toLowerCase(Locale.ROOT) + ":";
        int userCaches = (int) api.userCaches().caches().keySet().stream()
                .filter(key -> key.startsWith(cachePrefix))
                .count();
        return new OwnerCleanupStats(pluginName, safeReason(reason), commandRoutes, placeholderRoutes, guiSessions, dialogSessions, formSessions,
                schedulerTasks, storageUnits, executors, hooks, userCaches, System.currentTimeMillis(), tookMillis, failures);
    }

    private void flushUserCachesAsync(LeDatPlatformApi api, Plugin owner) {
        api.userCaches().flush(owner).whenComplete((ignored, error) -> {
            if (error != null) {
                platformPlugin.getLogger().log(Level.WARNING,
                        "Owner user-cache flush failed: " + owner.getName(), error);
            }
            try {
                api.userCaches().cleanup(owner);
            } catch (Throwable cleanupError) {
                platformPlugin.getLogger().log(Level.WARNING,
                        "Owner user-cache cleanup failed: " + owner.getName(), cleanupError);
            }
        });
    }

    private void shutdownExecutorsAsync(LeDatPlatformApi api, Plugin owner) {
        api.executors().shutdownAsync(owner).whenComplete((ignored, error) -> {
            if (error != null) {
                platformPlugin.getLogger().log(Level.WARNING,
                        "Owner executor shutdown failed: " + owner.getName(), error);
            }
        });
    }

    private void closeStorageAsync(LeDatPlatformApi api, Plugin owner) {
        api.database().closePluginAsync(owner.getName()).whenComplete((ignored, error) -> {
            if (error != null) {
                platformPlugin.getLogger().log(Level.WARNING,
                        "Owner storage close failed: " + owner.getName(), error);
            }
        });
    }

    private boolean ownerMatches(HookStatus status, String pluginName) {
        return status != null && status.owner() != null && status.owner().equalsIgnoreCase(pluginName);
    }

    private int runStep(Plugin owner, String step, Runnable action) {
        try {
            action.run();
            return 0;
        } catch (Throwable error) {
            platformPlugin.getLogger().log(Level.WARNING, "Owner cleanup step failed: " + owner.getName() + "/" + step, error);
            return 1;
        }
    }

    private void remember(OwnerCleanupStats stats) {
        recent.put(normalize(stats.pluginName()), stats);
        int limit = Math.max(1, platformPlugin.getConfig().getInt("owner-lifecycle.recent-limit", 20));
        if (recent.size() <= limit) {
            return;
        }
        List<OwnerCleanupStats> sorted = recentCleanups();
        for (int i = limit; i < sorted.size(); i++) {
            recent.remove(normalize(sorted.get(i).pluginName()));
        }
    }

    private String safeReason(String reason) {
        String value = reason == null || reason.isBlank() ? "unknown" : reason.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        return value.replaceAll("-+", "-").replaceAll("^-+|-+$", "");
    }

    private String normalize(String value) {
        String safe = value == null ? "unknown" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        safe = safe.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return safe.isBlank() ? "unknown" : safe;
    }

    public record LifecycleStats(
            boolean enabled,
            boolean cleanupOnPluginDisable,
            boolean refreshHooksOnPluginEnable,
            long cleanupRuns,
            long cleanupFailures,
            long hookRefreshes,
            int recentCleanupCount,
            Instant observedAt
    ) {
    }

    public record OwnerCleanupStats(
            String pluginName,
            String reason,
            int commandRoutes,
            int placeholderRoutes,
            int guiSessions,
            int dialogSessions,
            int formSessions,
            int schedulerTasks,
            int storageUnits,
            int executors,
            int hookEntries,
            int userCaches,
            long startedAtMillis,
            long tookMillis,
            int failures
    ) {
        public boolean hasState() {
            return commandRoutes > 0 || placeholderRoutes > 0 || guiSessions > 0 || dialogSessions > 0
                    || formSessions > 0 || schedulerTasks > 0 || storageUnits > 0 || executors > 0 || hookEntries > 0
                    || userCaches > 0;
        }

        private OwnerCleanupStats withResult(long tookMillis, int failures) {
            return new OwnerCleanupStats(pluginName, reason, commandRoutes, placeholderRoutes, guiSessions, dialogSessions, formSessions,
                    schedulerTasks, storageUnits, executors, hookEntries, userCaches, startedAtMillis, Math.max(0L, tookMillis), Math.max(0, failures));
        }

        public Map<String, String> asMap() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("plugin", pluginName);
            values.put("reason", reason);
            values.put("commands", String.valueOf(commandRoutes));
            values.put("placeholders", String.valueOf(placeholderRoutes));
            values.put("gui", String.valueOf(guiSessions));
            values.put("dialogs", String.valueOf(dialogSessions));
            values.put("forms", String.valueOf(formSessions));
            values.put("scheduler", String.valueOf(schedulerTasks));
            values.put("storage", String.valueOf(storageUnits));
            values.put("executors", String.valueOf(executors));
            values.put("hooks", String.valueOf(hookEntries));
            values.put("userCaches", String.valueOf(userCaches));
            values.put("failures", String.valueOf(failures));
            values.put("took", tookMillis + "ms");
            return values;
        }
    }
}
