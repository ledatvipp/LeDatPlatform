package vn.ledat.platform.core.doctor;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.LeDatPlatformApi;
import vn.ledat.platform.api.command.CommandRoute;
import vn.ledat.platform.api.command.CommandStats;
import vn.ledat.platform.api.config.ConfigValidationReport;
import vn.ledat.platform.api.database.DatabaseProvider;
import vn.ledat.platform.core.database.DefaultDatabaseProvider;
import vn.ledat.platform.api.doctor.DoctorRow;
import vn.ledat.platform.api.doctor.DoctorService;
import vn.ledat.platform.api.doctor.DoctorSnapshot;
import vn.ledat.platform.api.doctor.Severity;
import vn.ledat.platform.api.guard.GuardSnapshot;
import vn.ledat.platform.api.hook.HookStatus;
import vn.ledat.platform.api.scheduler.LeDatScheduler;
import vn.ledat.platform.core.dialog.DefaultDialogService;
import vn.ledat.platform.core.lifecycle.DefaultOwnerLifecycleService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class DefaultDoctorService implements DoctorService {
    private final Plugin plugin;
    private final java.util.function.Supplier<LeDatPlatformApi> apiSupplier;
    private final java.util.function.Supplier<DefaultOwnerLifecycleService> lifecycleSupplier;

    public DefaultDoctorService(Plugin plugin, java.util.function.Supplier<LeDatPlatformApi> apiSupplier) {
        this(plugin, apiSupplier, () -> null);
    }

    public DefaultDoctorService(Plugin plugin, java.util.function.Supplier<LeDatPlatformApi> apiSupplier, java.util.function.Supplier<DefaultOwnerLifecycleService> lifecycleSupplier) {
        this.plugin = plugin;
        this.apiSupplier = apiSupplier;
        this.lifecycleSupplier = lifecycleSupplier == null ? () -> null : lifecycleSupplier;
    }

    @Override
    public DoctorSnapshot snapshot() {
        List<DoctorRow> rows = new ArrayList<>();
        rows.addAll(check("core"));
        if (plugin.getConfig().getBoolean("production-profile.enabled", false)) {
            rows.addAll(check("production"));
        }
        rows.addAll(check("scheduler"));
        rows.addAll(check("executors"));
        rows.addAll(check("suite"));
        rows.addAll(check("lifecycle"));
        rows.addAll(check("storage"));
        rows.addAll(check("language"));
        rows.addAll(check("config"));
        rows.addAll(check("commands"));
        rows.addAll(check("hooks"));
        rows.addAll(check("placeholder"));
        rows.addAll(check("gui"));
        rows.addAll(check("dialogs"));
        rows.addAll(check("forms"));
        rows.addAll(check("serverlinks"));
        rows.addAll(check("audit"));
        int errors = (int) rows.stream().filter(row -> row.severity() == Severity.ERROR).count();
        int warnings = (int) rows.stream().filter(row -> row.severity() == Severity.WARN).count();
        Severity overall = errors > 0 ? Severity.ERROR : warnings > 0 ? Severity.WARN : Severity.OK;
        return new DoctorSnapshot(overall, warnings, errors, Instant.now(), List.copyOf(rows));
    }

    @Override
    public List<DoctorRow> check(String category) {
        LeDatPlatformApi api = apiSupplier.get();
        if (api == null) {
            return List.of(new DoctorRow(Severity.ERROR, "core.api", "missing", "LeDatPlatform API is not registered"));
        }
        String key = category == null ? "core" : category.toLowerCase(Locale.ROOT);
        List<DoctorRow> rows = new ArrayList<>();
        switch (key) {
            case "core" -> {
                rows.add(new DoctorRow(Severity.OK, "core.api", api.apiVersion(), "LeDatPlatform API registered"));
                rows.add(new DoctorRow(Severity.OK, "core.version", api.platformVersion(), "Runtime LeDatPlatform.jar"));
                boolean foliaClaim = foliaSupportedClaim();
                rows.add(new DoctorRow(foliaClaim ? Severity.OK : Severity.ERROR, "core.folia-claim", String.valueOf(foliaClaim), "plugin.yml folia-supported flag"));
                rows.add(new DoctorRow(Bukkit.getServicesManager().getRegistration(LeDatPlatformApi.class) != null ? Severity.OK : Severity.ERROR, "core.service", "services-manager", "Bukkit ServicesManager provider"));
            }
            case "production", "guards", "guard" -> {
                GuardSnapshot snapshot = api.guards().snapshot();
                CommandStats commandStats = api.commands().stats();
                List<String> rawThreads = api.executors().unregisteredLeDatThreadNames();
                rows.add(new DoctorRow(snapshot.productionProfileEnabled() ? Severity.OK : Severity.WARN, "production.profile", String.valueOf(snapshot.productionProfileEnabled()), "production-profile.enabled controls stricter runtime guards"));
                rows.add(new DoctorRow(!snapshot.productionProfileEnabled() || snapshot.mainThreadDbRejectEnabled() ? Severity.OK : Severity.ERROR, "production.db-main-thread-reject", String.valueOf(snapshot.mainThreadDbRejectEnabled()), "Production profile rejects synchronous DB borrows by default"));
                rows.add(new DoctorRow(snapshot.slowCommandHandlers() > 0 ? Severity.WARN : Severity.OK, "production.commands.slow", String.valueOf(snapshot.slowCommandHandlers()), "Command handlers above production-profile.command.slow-handler-warn-ms"));
                rows.add(new DoctorRow(commandStats.missingPluginYml() > 0 ? Severity.WARN : Severity.OK, "production.commands.unregistered", String.valueOf(commandStats.missingPluginYml()), "Paper native command routes that failed lifecycle registration"));
                rows.add(new DoctorRow(snapshot.unregisteredLeDatThreads() > 0 ? Severity.WARN : Severity.OK, "production.executors.raw", String.valueOf(snapshot.unregisteredLeDatThreads()), rawThreads.isEmpty() ? "No LeDat-named raw executor threads observed" : safe(String.join(", ", rawThreads.stream().limit(5).toList()))));
                rows.add(new DoctorRow(Severity.OK, "production.executors.managed", "owners=" + snapshot.managedExecutorOwners() + ", executors=" + snapshot.managedExecutorCount(), "Plugins can register/create owned executors through api.executors()"));
                rows.add(new DoctorRow(snapshot.disabledPluginTaskOwners() > 0 ? Severity.WARN : Severity.OK, "production.scheduler.disabled-owner-tasks", "owners=" + snapshot.disabledPluginTaskOwners() + ", tasks=" + snapshot.disabledPluginTrackedTasks(), "Tracked scheduler tasks remaining for disabled plugin owners"));
                rows.add(new DoctorRow(snapshot.guardedFailures() > 0 ? Severity.WARN : Severity.OK, "production.guarded-operations", "ops=" + snapshot.guardedOperations() + ", failures=" + snapshot.guardedFailures(), "api.guards().runGuarded operation counters"));
            }
            case "scheduler", "tasks" -> {
                int warnOwners = Math.max(1, plugin.getConfig().getInt("scheduler.diagnostics.warn-owner-count", 64));
                int warnTotalTasks = Math.max(1, plugin.getConfig().getInt("scheduler.diagnostics.warn-total-tracked-tasks", 256));
                int warnPluginTasks = Math.max(1, plugin.getConfig().getInt("scheduler.diagnostics.warn-plugin-tracked-tasks", 64));
                int warnStaleOneShot = Math.max(1, plugin.getConfig().getInt("scheduler.diagnostics.warn-stale-one-shot-tasks", 32));
                int listLimit = Math.max(1, plugin.getConfig().getInt("scheduler.diagnostics.list-limit", 20));
                long warnFailed = Math.max(0L, plugin.getConfig().getLong("scheduler.diagnostics.warn-failed-tasks", 1L));
                long warnSlow = Math.max(0L, plugin.getConfig().getLong("scheduler.diagnostics.warn-slow-tasks", 1L));
                LeDatScheduler scheduler = api.scheduler();
                Map<String, LeDatScheduler.SchedulerOwnerStats> ownerStats = scheduler.ownerStats();
                int totalTracked = ownerStats.values().stream().mapToInt(LeDatScheduler.SchedulerOwnerStats::trackedTasks).sum();
                int totalRepeating = ownerStats.values().stream().mapToInt(LeDatScheduler.SchedulerOwnerStats::repeatingTasks).sum();
                int totalOneShot = ownerStats.values().stream().mapToInt(LeDatScheduler.SchedulerOwnerStats::oneShotTasks).sum();
                rows.add(new DoctorRow(Severity.OK, "scheduler.mode", scheduler.implementationName(), "Unified FoliaLib-backed scheduler active"));
                rows.add(new DoctorRow(Severity.OK, "scheduler.folia", String.valueOf(scheduler.isFolia()), scheduler.isFolia() ? "Folia/region-thread runtime detected" : "Paper/Canvas-compatible global-thread fallback runtime"));
                rows.add(new DoctorRow(Severity.OK, "scheduler.primary-thread", String.valueOf(scheduler.isPrimaryThread()), "Current caller thread marker; false is normal from async/global callbacks"));
                rows.add(new DoctorRow(Severity.OK, "scheduler.region-api", "entity+region+async+teleport", "Core scheduler exposes entityLater/entityRepeat/regionLater/regionRepeat/teleportAsync"));
                rows.add(new DoctorRow(scheduler.failedTasks() >= warnFailed && scheduler.failedTasks() > 0 ? Severity.WARN : Severity.OK, "scheduler.failed", String.valueOf(scheduler.failedTasks()), "Scheduled tasks that threw an exception since startup"));
                rows.add(new DoctorRow(scheduler.slowTasks() >= warnSlow && scheduler.slowTasks() > 0 ? Severity.WARN : Severity.OK, "scheduler.slow", String.valueOf(scheduler.slowTasks()), "Tasks exceeding scheduler.slow-task-warn-ms / async-warning-ms"));
                rows.add(new DoctorRow(Severity.OK, "scheduler.counters", "submitted=" + scheduler.submittedTasks() + ", completed=" + scheduler.completedTasks() + ", cancelled=" + scheduler.cancelledTasks(), "Runtime task counters tracked by LeDatPlatform"));
                rows.add(new DoctorRow(totalTracked > warnTotalTasks ? Severity.WARN : Severity.OK, "scheduler.tracked-total", String.valueOf(totalTracked), "Tracked active task handles across all plugins"));
                rows.add(new DoctorRow(totalOneShot > warnStaleOneShot ? Severity.WARN : Severity.OK, "scheduler.tracked-kinds", "repeating=" + totalRepeating + ", oneShot=" + totalOneShot, "Active tracked repeating/one-shot split; oneShot should usually drain quickly"));
                rows.add(new DoctorRow(ownerStats.size() > warnOwners ? Severity.WARN : Severity.OK, "scheduler.owners", String.valueOf(ownerStats.size()), "Plugins owning active tracked scheduler tasks"));
                rows.add(new DoctorRow(Severity.OK, "scheduler.tracked-platform", String.valueOf(scheduler.trackedTaskCount(plugin)), "Active tracked tasks owned by LeDatPlatform"));
                ownerStats.values().stream()
                        .sorted(Comparator.comparingInt(LeDatScheduler.SchedulerOwnerStats::trackedTasks).reversed().thenComparing(LeDatScheduler.SchedulerOwnerStats::pluginName))
                        .limit(listLimit)
                        .forEach(stats -> rows.add(new DoctorRow(stats.trackedTasks() > warnPluginTasks ? Severity.WARN : Severity.OK, "scheduler.owner." + normalize(stats.pluginName()), String.valueOf(stats.trackedTasks()), "repeating=" + stats.repeatingTasks() + ", oneShot=" + stats.oneShotTasks() + ", cancelledTracked=" + stats.cancelledTasks())));
            }

            case "executors", "executor", "threads" -> {
                Map<String, vn.ledat.platform.api.executor.ManagedExecutorService.ExecutorStats> stats = api.executors().stats();
                List<String> rawThreads = api.executors().unregisteredLeDatThreadNames();
                int totalQueued = stats.values().stream().mapToInt(vn.ledat.platform.api.executor.ManagedExecutorService.ExecutorStats::queuedTasks).sum();
                int totalActive = stats.values().stream().mapToInt(vn.ledat.platform.api.executor.ManagedExecutorService.ExecutorStats::activeThreads).sum();
                long rejected = stats.values().stream().mapToLong(vn.ledat.platform.api.executor.ManagedExecutorService.ExecutorStats::rejectedTasks).sum();
                long failed = stats.values().stream().mapToLong(vn.ledat.platform.api.executor.ManagedExecutorService.ExecutorStats::failedTasks).sum();
                int warnQueue = Math.max(1, plugin.getConfig().getInt("production-profile.executors.warn-queue-size", 512));
                rows.add(new DoctorRow(Severity.OK, "executors.managed", "count=" + stats.size() + ", active=" + totalActive, "ManagedExecutorService registry for child plugin async workers"));
                rows.add(new DoctorRow(totalQueued > warnQueue ? Severity.WARN : Severity.OK, "executors.queue", String.valueOf(totalQueued), "Total queued tasks across managed executors"));
                rows.add(new DoctorRow(rejected > 0L || failed > 0L ? Severity.WARN : Severity.OK, "executors.failures", "failed=" + failed + ", rejected=" + rejected, "Managed executor failure/rejection counters"));
                rows.add(new DoctorRow(rawThreads.isEmpty() ? Severity.OK : Severity.WARN, "executors.raw-ledat-threads", String.valueOf(rawThreads.size()), rawThreads.isEmpty() ? "No unregistered LeDat-named threads detected" : safe(String.join(", ", rawThreads.stream().limit(5).toList()))));
                int limit = Math.max(1, plugin.getConfig().getInt("doctor.suite.list-limit", 40));
                stats.values().stream()
                        .sorted(Comparator.comparingInt(vn.ledat.platform.api.executor.ManagedExecutorService.ExecutorStats::queuedTasks).reversed().thenComparing(vn.ledat.platform.api.executor.ManagedExecutorService.ExecutorStats::key))
                        .limit(Math.min(limit, 20))
                        .forEach(executor -> rows.add(new DoctorRow(executor.rejectedTasks() > 0 || executor.failedTasks() > 0 || executor.queuedTasks() > warnQueue ? Severity.WARN : Severity.OK,
                                "executors.owner." + normalize(executor.key()),
                                "queued=" + executor.queuedTasks() + ", active=" + executor.activeThreads(),
                                "submitted=" + executor.submittedTasks() + ", completed=" + executor.completedTasks() + ", failed=" + executor.failedTasks() + ", rejected=" + executor.rejectedTasks())));
            }

            case "lifecycle", "owner-lifecycle", "owners" -> {
                DefaultOwnerLifecycleService lifecycle = lifecycleSupplier.get();
                if (lifecycle == null) {
                    rows.add(new DoctorRow(Severity.WARN, "lifecycle.listener", "missing", "Owner lifecycle cleanup service is not registered"));
                    break;
                }
                DefaultOwnerLifecycleService.LifecycleStats stats = lifecycle.stats();
                rows.add(new DoctorRow(stats.enabled() ? Severity.OK : Severity.WARN, "lifecycle.enabled", String.valueOf(stats.enabled()), "Owner cleanup safety net master toggle"));
                rows.add(new DoctorRow(stats.cleanupOnPluginDisable() ? Severity.OK : Severity.WARN, "lifecycle.plugin-disable", String.valueOf(stats.cleanupOnPluginDisable()), "Cleanup platform-owned child plugin state on PluginDisableEvent"));
                rows.add(new DoctorRow(Severity.OK, "lifecycle.hook-refresh", String.valueOf(stats.refreshHooksOnPluginEnable()), "Refresh optional hook registry after PluginEnableEvent"));
                rows.add(new DoctorRow(stats.cleanupFailures() > 0 ? Severity.WARN : Severity.OK, "lifecycle.cleanups", "runs=" + stats.cleanupRuns() + ", failures=" + stats.cleanupFailures(), "Command/gui/dialog/storage/scheduler/executor owner cleanup counters"));
                rows.add(new DoctorRow(Severity.OK, "lifecycle.recent", String.valueOf(stats.recentCleanupCount()), "Recently cleaned plugin owners retained for diagnostics"));
                int limit = Math.max(1, plugin.getConfig().getInt("owner-lifecycle.recent-limit", 20));
                lifecycle.recentCleanups().stream().limit(Math.min(limit, 10)).forEach(cleanup -> rows.add(new DoctorRow(
                        cleanup.failures() > 0 ? Severity.WARN : Severity.OK,
                        "lifecycle.cleanup." + normalize(cleanup.pluginName()),
                        cleanup.reason(),
                        "commands=" + cleanup.commandRoutes()
                                + ", placeholders=" + cleanup.placeholderRoutes()
                                + ", gui=" + cleanup.guiSessions()
                                + ", dialogs=" + cleanup.dialogSessions()
                                + ", forms=" + cleanup.formSessions()
                                + ", scheduler=" + cleanup.schedulerTasks()
                                + ", executors=" + cleanup.executors()
                                + ", storage=" + cleanup.storageUnits()
                                + ", hooks=" + cleanup.hookEntries()
                                + ", failures=" + cleanup.failures()
                                + ", took=" + cleanup.tookMillis() + "ms")));
            }

            case "suite", "plugins", "integration" -> {
                List<Plugin> ledatPlugins = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                        .filter(candidate -> candidate.getName().startsWith("LeDat"))
                        .sorted(Comparator.comparing(Plugin::getName, String.CASE_INSENSITIVE_ORDER))
                        .toList();
                List<Plugin> childPlugins = ledatPlugins.stream()
                        .filter(candidate -> !candidate.getName().equalsIgnoreCase(plugin.getName()))
                        .toList();
                Set<String> commandPlugins = api.commands().routes().values().stream()
                        .map(route -> normalize(route.pluginName()))
                        .collect(Collectors.toSet());
                Set<String> placeholderPlugins = api.placeholders().routes().stream()
                        .map(route -> normalize(route.pluginName()))
                        .collect(Collectors.toSet());
                Set<String> schedulerOwners = api.scheduler().ownerStats().keySet().stream()
                        .map(this::normalize)
                        .collect(Collectors.toSet());
                Set<String> storageOwners = api.database().storageUnits().values().stream()
                        .map(unit -> normalize(unit.pluginId()))
                        .collect(Collectors.toSet());

                long disabled = childPlugins.stream().filter(candidate -> !candidate.isEnabled()).count();
                long commandsMissing = childPlugins.stream()
                        .filter(candidate -> declaredCommandCount(candidate) > 0)
                        .filter(candidate -> !commandPlugins.contains(normalize(candidate.getName())))
                        .count();
                long foliaMissing = childPlugins.stream().filter(candidate -> !foliaSupportedClaim(candidate)).count();
                rows.add(new DoctorRow(Severity.OK, "suite.plugins", "loaded=" + childPlugins.size() + ", enabled=" + (childPlugins.size() - disabled), "Loaded LeDat child plugins visible to LeDatPlatform"));
                rows.add(new DoctorRow(foliaMissing > 0 ? Severity.WARN : Severity.OK, "suite.folia-claims", "missing=" + foliaMissing, "Child plugin.yml folia-supported markers"));
                rows.add(new DoctorRow(commandsMissing > 0 && plugin.getConfig().getBoolean("doctor.suite.warn-missing-command-routes", true) ? Severity.WARN : Severity.OK, "suite.command-adoption", "missing=" + commandsMissing, "Child plugins with plugin.yml commands should register through LeDatPlatform CommandService"));
                rows.add(new DoctorRow(Severity.OK, "suite.placeholder-adoption", String.valueOf(placeholderPlugins.size()), "Child plugins exposing canonical %ledat_*% placeholder routes"));
                rows.add(new DoctorRow(Severity.OK, "suite.scheduler-owners", String.valueOf(schedulerOwners.size()), "Plugins owning active tracked scheduler tasks"));
                rows.add(new DoctorRow(Severity.OK, "suite.storage-owners", String.valueOf(storageOwners.size()), "Plugins with opened Data Service storage units"));
                boolean sharedMysqlReady = api.database().mode() == vn.ledat.platform.api.database.StorageMode.MYSQL
                        && api.database().enabled()
                        && api.database().healthy();
                long platformDbRequested = childPlugins.stream().filter(this::platformDatabaseRequested).count();
                long platformDbRequired = childPlugins.stream().filter(this::platformDatabaseRequired).count();
                rows.add(new DoctorRow(platformDbRequested > 0 && !sharedMysqlReady ? (platformDbRequired > 0 ? Severity.ERROR : Severity.WARN) : Severity.OK,
                        "suite.platform-db",
                        "requested=" + platformDbRequested + ", required=" + platformDbRequired + ", sharedMysql=" + sharedMysqlReady,
                        "Child plugins using ledat-suite.database/suite.database platform mode need LeDatPlatform storage.mode=mysql and healthy provider"));

                int limit = Math.max(1, plugin.getConfig().getInt("doctor.suite.list-limit", 40));
                for (Plugin child : childPlugins.stream().limit(limit).toList()) {
                    String pluginKey = normalize(child.getName());
                    int commandCount = declaredCommandCount(child);
                    boolean hasCommandRoute = commandCount == 0 || commandPlugins.contains(pluginKey);
                    boolean hasFoliaClaim = foliaSupportedClaim(child);
                    Severity severity = !child.isEnabled() && plugin.getConfig().getBoolean("doctor.suite.warn-plugin-not-enabled", true)
                            ? Severity.WARN
                            : (!hasCommandRoute || !hasFoliaClaim ? Severity.WARN : Severity.OK);
                    boolean platformDb = platformDatabaseRequested(child);
                    boolean platformDbRequire = platformDatabaseRequired(child);
                    String state = "enabled=" + child.isEnabled()
                            + ", commands=" + commandCount + "/" + hasCommandRoute
                            + ", placeholders=" + placeholderPlugins.contains(pluginKey)
                            + ", storage=" + storageOwners.contains(pluginKey)
                            + ", platformDb=" + platformDb + (platformDbRequire ? "/required" : "")
                            + ", scheduler=" + schedulerOwners.contains(pluginKey)
                            + ", folia=" + hasFoliaClaim;
                    rows.add(new DoctorRow(severity, "suite.plugin." + pluginKey, child.getDescription().getVersion(), state));
                }
            }
            case "storage", "database" -> {
                DatabaseProvider.DatabaseStats stats = api.database().stats();
                DatabaseProvider.StorageHealth health = api.database().healthSnapshot();
                Severity modeSeverity = stats.healthy() ? Severity.OK : (stats.mode().sql() ? Severity.ERROR : Severity.WARN);
                rows.add(new DoctorRow(modeSeverity, "storage.mode", stats.mode().name(), api.database().status()));
                rows.add(new DoctorRow(health.ok() ? Severity.OK : Severity.WARN, "storage.health", "units=" + health.units() + ", unhealthy=" + health.unhealthyUnits() + ", dirtyYaml=" + health.dirtyYamlStores(), safe(health.lastError())));
                rows.add(new DoctorRow(Severity.OK, "storage.pool", "active=" + stats.activeConnections() + ", idle=" + stats.idleConnections() + ", waiting=" + stats.waitingThreads(), "SQL pool stats; zero in YAML mode"));
                rows.add(new DoctorRow(stats.queuedTasks() > plugin.getConfig().getInt("storage.executor.warn-queue-size", 2048) ? Severity.WARN : Severity.OK, "storage.executor", "pool=" + stats.poolSize() + ", queued=" + stats.queuedTasks() + ", rejected=" + stats.rejectedTasks(), "Async database executor queue and rejection counters"));
                rows.add(new DoctorRow(stats.failedTasks() > 0 || stats.rejectedTasks() > 0 ? Severity.WARN : Severity.OK, "storage.tasks", "submitted=" + stats.submittedTasks() + ", completed=" + stats.completedTasks() + ", failed=" + stats.failedTasks() + ", slow=" + stats.slowTasks(), safe(stats.lastError())));
                rows.add(new DoctorRow(Severity.OK, "storage.layout", stats.storageRoot(), "Standard child plugin layout: plugins/<plugin>/storage/data.yml or data.db"));
                rows.add(new DoctorRow(Severity.OK, "storage.units", "yaml=" + stats.yamlStores() + ", sqlite=" + stats.sqliteStores(), "Managed Data Service units currently opened"));
                rows.add(new DoctorRow(health.queuePressure() || health.waitingPressure() ? Severity.WARN : Severity.OK, "storage.pressure", "queued=" + health.queuedTasks() + ", waiting=" + stats.waitingThreads(), "Queue/waiting pressure snapshot for 200-300 player runtime"));
                boolean compatProvider = System.getProperties().get("ledat.platform.database.provider.v1") != null;
                boolean compatEnabled = plugin.getConfig().getBoolean("database.compatibility-provider.enabled", true) && (stats.mode() == vn.ledat.platform.api.database.StorageMode.MYSQL || stats.mode() == vn.ledat.platform.api.database.StorageMode.SHARED_MYSQL) && stats.enabled() && stats.healthy();
                rows.add(new DoctorRow(compatEnabled ? Severity.OK : (plugin.getConfig().getBoolean("database.compatibility-provider.enabled", true) ? Severity.WARN : Severity.OK), "storage.compat-provider", "published=" + compatProvider + ", enabled=" + compatEnabled, "Legacy/shaded modules can discover ledat.platform.database.provider.v1 when shared MySQL is healthy"));
                int failedUnits = health.unhealthyUnits();
                int dirtyYamlUnits = health.dirtyYamlStores();
                rows.add(new DoctorRow(failedUnits > 0 ? Severity.WARN : Severity.OK, "storage.unit-health", String.valueOf(failedUnits), "Units with failed tasks or unhealthy state"));
                rows.add(new DoctorRow(dirtyYamlUnits > 0 ? Severity.WARN : Severity.OK, "storage.yaml-dirty", String.valueOf(dirtyYamlUnits), "Dirty YAML stores waiting for async flush"));
                if (api.database() instanceof DefaultDatabaseProvider provider) {
                    rows.add(new DoctorRow(provider.sqliteCheckpointFailures() > 0 ? Severity.WARN : Severity.OK, "storage.sqlite-checkpoint", "ok=" + provider.sqliteCheckpoints() + ", failed=" + provider.sqliteCheckpointFailures(), "Managed SQLite WAL checkpoint counters; close uses TRUNCATE by default"));
                    rows.add(new DoctorRow(provider.sqliteQuickCheckFailures() > 0 ? Severity.ERROR : Severity.OK, "storage.sqlite-quick-check", String.valueOf(provider.sqliteQuickCheckFailures()), "SQLite quick_check failures since platform enable"));
                    rows.add(new DoctorRow(provider.sqliteIntegrityCheckFailures() > 0 ? Severity.ERROR : Severity.OK, "storage.sqlite-integrity-check", String.valueOf(provider.sqliteIntegrityCheckFailures()), "SQLite integrity_check failures since platform enable"));
                    rows.add(new DoctorRow(provider.storageVerifyFailures() > 0 ? Severity.WARN : Severity.OK, "storage.verify", "runs=" + provider.storageVerifyRuns() + ", failed=" + provider.storageVerifyFailures(), "Manual/staging verification through /ledatplatform storage verify [plugin|all] [--deep]"));
                    rows.add(new DoctorRow(provider.yamlAtomicWriteFailures() > 0 ? Severity.WARN : Severity.OK, "storage.yaml-atomic", "writes=" + provider.yamlAtomicWrites() + ", failed=" + provider.yamlAtomicWriteFailures(), "YAML stores save through tmp + atomic replace when supported"));
                }
                boolean mainThreadReject = effectiveMainThreadDbReject();
                rows.add(new DoctorRow(mainThreadReject ? Severity.OK : Severity.WARN, "storage.main-thread-guard", String.valueOf(mainThreadReject), "Effective SQL borrow guard; production-profile can enforce this without editing storage.safety"));
            }
            case "language", "languages" -> {
                var stats = api.languages().stats();
                int warnBundles = Math.max(1, plugin.getConfig().getInt("language.diagnostics.warn-loaded-bundles", 256));
                int warnMissing = Math.max(0, plugin.getConfig().getInt("language.diagnostics.warn-missing-warning-keys", 80));
                int warnInvalid = Math.max(0, plugin.getConfig().getInt("language.diagnostics.warn-invalid-warning-keys", 20));
                int warnBossBars = Math.max(1, plugin.getConfig().getInt("language.diagnostics.warn-active-bossbars", 320));
                rows.add(new DoctorRow(api.languages().has(plugin, api.languages().defaultLocale(), "prefix") ? Severity.OK : Severity.WARN, "language.default", api.languages().defaultLocale(), "Default locale bundle prefix exists"));
                rows.add(new DoctorRow(api.languages().has(plugin, api.languages().fallbackLocale(), "prefix") ? Severity.OK : Severity.ERROR, "language.fallback", api.languages().fallbackLocale(), "Fallback locale bundle prefix exists"));
                rows.add(new DoctorRow(Severity.OK, "language.supported", String.join(",", api.languages().supportedLocales(plugin)), "Detected platform locale bundles"));
                rows.add(new DoctorRow(api.languages().languageFolder(plugin).exists() ? Severity.OK : Severity.WARN, "language.folder", api.languages().languageFolder(plugin).getPath(), "Per-plugin languages/<locale>.yml folder"));
                rows.add(new DoctorRow(api.languages().has(plugin, api.languages().fallbackLocale(), "gui.expired") ? Severity.OK : Severity.WARN, "language.gui-expired", "gui.expired", "Platform GUI session expiry fallback message"));
                rows.add(new DoctorRow(Severity.OK, "language.log-throttle", "missing=" + plugin.getConfig().getLong("language.missing-message-log-interval-ms", 60000L) + "ms, invalid=" + plugin.getConfig().getLong("language.invalid-message-log-interval-ms", 60000L) + "ms", "Language missing/invalid message warnings are rate-limited"));
                rows.add(new DoctorRow(stats.loadedBundles() > warnBundles ? Severity.WARN : Severity.OK, "language.cache", "plugins=" + stats.registeredPlugins() + ", bundles=" + stats.loadedBundles(), "Loaded language bundle cache; high counts may indicate plugins registering too many locale variants"));
                rows.add(new DoctorRow(Severity.OK, "language.preferences", "saved=" + stats.savedLocales() + ", listeners=" + stats.listeners(), "Suite language preferences are persisted by LeDatPlatform and broadcast to child plugins"));
                rows.add(new DoctorRow(stats.activeBossBars() > warnBossBars ? Severity.WARN : Severity.OK, "language.bossbars", "players=" + stats.playersWithBossBars() + ", bars=" + stats.activeBossBars(), "Active language bossbars are player-scoped and cleaned on quit"));
                rows.add(new DoctorRow(stats.missingMessageWarningKeys() > warnMissing || stats.invalidMessageWarningKeys() > warnInvalid ? Severity.WARN : Severity.OK, "language.message-quality", "missingKeys=" + stats.missingMessageWarningKeys() + ", invalidKeys=" + stats.invalidMessageWarningKeys(), "Unique missing/invalid message warnings since last language reload"));
            }
            case "config" -> {
                ConfigValidationReport report = api.configs().validatePlatformConfig();
                rows.add(new DoctorRow(report.ok() ? Severity.OK : Severity.ERROR, "config.validation", "errors=" + report.errorCount() + ", warnings=" + report.warningCount(), String.join(" | ", report.errors())));
                for (String warning : report.warnings()) {
                    rows.add(new DoctorRow(Severity.WARN, "config.warning", "warn", warning));
                }
            }
            case "commands", "command" -> {
                CommandStats stats = api.commands().stats();
                int legacyThreshold = Math.max(0, plugin.getConfig().getInt("command.diagnostics.legacy-warn-threshold", 1));
                boolean missingYmlIsError = plugin.getConfig().getBoolean("command.diagnostics.missing-plugin-yml-error", false);
                rows.add(new DoctorRow(Severity.OK, "commands.registry", "roots=" + stats.rootCommands() + ", nodes=" + stats.commandNodes(), "Paper Brigadier routes tracked by LeDatPlatform CommandService"));
                rows.add(new DoctorRow(stats.legacyDelegates() >= legacyThreshold && stats.legacyDelegates() > 0 ? Severity.WARN : Severity.OK, "commands.legacy-delegates", String.valueOf(stats.legacyDelegates()), "Command nodes still marked as legacy handler delegates; migrate these to typed Brigadier nodes gradually"));
                rows.add(new DoctorRow(stats.missingPluginYml() > 0 ? (missingYmlIsError ? Severity.ERROR : Severity.WARN) : Severity.OK, "commands.paper-native", String.valueOf(stats.missingPluginYml()), "Command nodes that failed Paper lifecycle registration"));
                rows.add(new DoctorRow(Severity.OK, "commands.plugins", String.valueOf(stats.pluginCount()), "Plugins using platform command registry"));
                rows.add(new DoctorRow(Severity.OK, "commands.guards", "playerOnly=" + stats.playerOnlyNodes() + ", cooldown=" + stats.cooldownNodes() + ", rateLimit=" + stats.rateLimitedNodes(), "Nodes using built-in player-only/cooldown/rate-limit guards"));
                rows.add(new DoctorRow(stats.slowHandlers() > 0 ? Severity.WARN : Severity.OK, "commands.runtime", "executed=" + stats.executedHandlers() + ", slow=" + stats.slowHandlers(), "Runtime command handler counters since platform enable; slow logs are throttled by command.diagnostics.slow-handler-log-interval-ms"));
                rows.add(new DoctorRow(Severity.OK, "commands.suggestions", "max=" + plugin.getConfig().getInt("command.paper-native.max-suggestions", 128), "Paper Brigadier suggestion responses are capped to reduce tab-complete spikes"));
                List<String> requiredKeys = List.of("command.no-permission", "command.player-only", "command.cooldown", "command.rate-limited", "command.usage", "command.invalid-args", "command.unknown", "command.error");
                for (String messageKey : requiredKeys) {
                    rows.add(new DoctorRow(api.languages().has(plugin, api.languages().fallbackLocale(), messageKey) ? Severity.OK : Severity.ERROR, "commands.message." + messageKey.substring("command.".length()), messageKey, "Platform fallback message key for CommandService"));
                }
                int limit = Math.max(1, plugin.getConfig().getInt("command.diagnostics.list-limit", 60));
                api.commands().routes().values().stream()
                        .filter(CommandRoute::legacyDelegate)
                        .sorted(java.util.Comparator.comparing(CommandRoute::pluginName).thenComparing(CommandRoute::path))
                        .limit(Math.min(limit, 20))
                        .forEach(route -> rows.add(new DoctorRow(Severity.WARN, "commands.legacy." + normalize(route.pluginName()) + "." + normalize(route.path()), route.path(), safe(route.legacyDetail() == null || route.legacyDetail().isBlank() ? route.pluginName() : route.legacyDetail()))));
                api.commands().routes().values().stream()
                        .filter(route -> !route.declaredInPluginYml())
                        .sorted(java.util.Comparator.comparing(CommandRoute::pluginName).thenComparing(CommandRoute::path))
                        .limit(Math.min(limit, 20))
                        .forEach(route -> rows.add(new DoctorRow(missingYmlIsError ? Severity.ERROR : Severity.WARN, "commands.unregistered." + normalize(route.pluginName()) + "." + normalize(route.rootName()), route.rootName(), "Paper lifecycle registration failed for /" + route.rootName() + " in " + route.pluginName())));
            }
            case "hooks" -> {
                List<HookStatus> statuses = api.hooks().statuses();
                if (statuses.isEmpty()) {
                    rows.add(new DoctorRow(Severity.WARN, "hooks.registry", "empty", "No hooks registered"));
                } else {
                    for (HookStatus status : statuses) {
                        rows.add(new DoctorRow(status.available() ? Severity.OK : Severity.WARN, "hook." + status.hookName(), status.available() ? "available" : "missing", status.detail()));
                    }
                }
            }
            case "placeholder", "placeholders" -> {
                boolean papiEnabled = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
                rows.add(new DoctorRow(papiEnabled ? Severity.OK : Severity.WARN, "placeholderapi.plugin", papiEnabled ? "available" : "missing", "PlaceholderAPI optional hook"));
                rows.add(new DoctorRow(Severity.OK, "placeholder.routes", String.valueOf(api.placeholders().routes().size()), "Canonical %ledat_*% route count"));
            }
            case "gui" -> {
                var stats = api.guis().stats();
                int warnSessions = Math.max(1, plugin.getConfig().getInt("gui.diagnostics.warn-active-sessions", 200));
                int warnReopenGuards = Math.max(1, plugin.getConfig().getInt("gui.diagnostics.warn-reopen-guards", 32));
                long warnBlockedClicks = Math.max(0L, plugin.getConfig().getLong("gui.diagnostics.warn-blocked-rapid-clicks", 1000L));
                long warnFailedOpens = Math.max(0L, plugin.getConfig().getLong("gui.diagnostics.warn-failed-opens", 1L));
                rows.add(new DoctorRow(Severity.OK, "gui.base", "registered", "Holder/session/click/drag/quit guard listener active"));
                rows.add(new DoctorRow(stats.sessions() > warnSessions ? Severity.WARN : Severity.OK, "gui.sessions", String.valueOf(stats.sessions()), "Active platform GUI sessions"));
                rows.add(new DoctorRow(Severity.OK, "gui.reopen-guard", "enabled", "Session id guard prevents old close events from cleaning new sessions"));
                rows.add(new DoctorRow(stats.reopenGuards() > warnReopenGuards ? Severity.WARN : Severity.OK, "gui.reopen-guard-pending", String.valueOf(stats.reopenGuards()), "Pending controlled close guards during GUI reopen"));
                rows.add(new DoctorRow(Severity.OK, "gui.bottom-click-guard", "configurable", "Builders can keep bottom inventory locked or allow it per GUI"));
                rows.add(new DoctorRow(Severity.OK, "gui.session-ttl", plugin.getConfig().getLong("gui.session-ttl-seconds", 300L) + "s", "Expired platform GUI sessions are cleaned and blocked on next click"));
                rows.add(new DoctorRow(Severity.OK, "gui.click-debounce", plugin.getConfig().getLong("gui.click-debounce-ms", 150L) + "ms", "Rapid duplicate GUI clicks are ignored per player"));
                rows.add(new DoctorRow(stats.blockedRapidClicks() > warnBlockedClicks ? Severity.WARN : Severity.OK, "gui.click-pressure", "blocked=" + stats.blockedRapidClicks() + ", debouncedPlayers=" + stats.clickDebounceEntries(), "Rapid duplicate click counter since platform enable"));
                rows.add(new DoctorRow(stats.failedOpens() >= warnFailedOpens && stats.failedOpens() > 0 ? Severity.WARN : Severity.OK, "gui.failed-opens", String.valueOf(stats.failedOpens()), "GUI inventory create/open failures since platform enable"));
                rows.add(new DoctorRow(Severity.OK, "gui.expired-cleanups", String.valueOf(stats.expiredSessionCleanups()), "Expired GUI sessions cleaned by background task"));
                rows.add(new DoctorRow(Severity.OK, "gui.item-style", "non-italic", "Platform GUI items normalize display name/lore with ITALIC=false"));
            }
            case "dialog", "dialogs" -> {
                boolean nativeAvailable = api.dialogs().nativeDialogsAvailable();
                boolean fallback = api.dialogs().fallbackEnabled();
                var stats = api.dialogs().stats();
                int warnSessions = Math.max(1, plugin.getConfig().getInt("dialogs.diagnostics.warn-active-sessions", 200));
                int warnNativeActions = Math.max(1, plugin.getConfig().getInt("dialogs.diagnostics.warn-native-actions", 512));
                long warnNativeFallbacks = Math.max(0L, plugin.getConfig().getLong("dialogs.diagnostics.warn-native-fallbacks", 1L));
                long warnFailedOpens = Math.max(0L, plugin.getConfig().getLong("dialogs.diagnostics.warn-failed-opens", 1L));
                rows.add(new DoctorRow(nativeAvailable ? Severity.OK : Severity.WARN, "dialogs.native", nativeAvailable ? "available" : "unavailable", api.dialogs().nativeStatus()));
                rows.add(new DoctorRow(fallback ? Severity.OK : Severity.WARN, "dialogs.fallback", String.valueOf(fallback), "Inventory GUI fallback for unsupported runtime/client"));
                rows.add(new DoctorRow(stats.sessions() > warnSessions ? Severity.WARN : Severity.OK, "dialogs.sessions", String.valueOf(stats.sessions()), "Active platform dialog sessions"));
                rows.add(new DoctorRow(stats.nativeActions() > warnNativeActions ? Severity.WARN : Severity.OK, "dialogs.native-actions", String.valueOf(stats.nativeActions()), "Pending native dialog action callbacks; high values may indicate stale native sessions"));
                rows.add(new DoctorRow(stats.nativeFallbacks() >= warnNativeFallbacks && stats.nativeFallbacks() > 0 ? Severity.WARN : Severity.OK, "dialogs.native-fallbacks", String.valueOf(stats.nativeFallbacks()), "Native dialog failures that fell back to inventory GUI"));
                rows.add(new DoctorRow(stats.failedOpens() >= warnFailedOpens && stats.failedOpens() > 0 ? Severity.WARN : Severity.OK, "dialogs.failed-opens", String.valueOf(stats.failedOpens()), "Dialog opens rejected because native/fallback routes were unavailable"));
                rows.add(new DoctorRow(Severity.OK, "dialogs.templates", String.valueOf(api.dialogs().templateCount(plugin)), "Platform dialog templates loaded from dialogs/*.yml and inventories/*.yml"));
                rows.add(new DoctorRow(Severity.OK, "dialogs.template-cache", String.valueOf(stats.templateOwnerCacheSize()), "Owners whose bundled dialog templates have been scanned/saved"));
                rows.add(new DoctorRow(Severity.OK, "dialogs.display-overrides", String.valueOf(stats.displayOverrides()), "Players with explicit native/fallback display override"));
                rows.add(new DoctorRow(Severity.OK, "dialogs.expired-cleanups", String.valueOf(stats.expiredSessionCleanups()), "Expired dialog sessions cleaned by platform"));
                rows.add(new DoctorRow(api.dialogs().richObjectComponentsAvailable() ? Severity.OK : Severity.WARN, "dialogs.rich-tags", api.dialogs().richObjectComponentsAvailable() ? "object-components" : "fallback-glyph", "Supports <item:material> and <head:player> tags when runtime Adventure object components are available"));
                rows.add(new DoctorRow(Severity.OK, "dialogs.min-client", plugin.getConfig().getString("dialogs.native.min-client-version", "1.21.7"), "Native dialog minimum client version gate"));
                if (api.dialogs() instanceof DefaultDialogService service) {
                    rows.add(new DoctorRow(Severity.OK, "dialogs.client-detector", service.clientDetectorStatus(), "ViaVersion detector status; unknown clients follow config fallback policy"));
                    rows.add(new DoctorRow(api.dialogs().richObjectComponentsAvailable() ? Severity.OK : Severity.WARN, "dialogs.rich-status", service.richObjectStatus(), "Adventure object component bridge for item sprites and player heads"));
                }
            }
            case "forms", "bedrock-forms", "bedrockform" -> {
                boolean enabled = plugin.getConfig().getBoolean("forms.enabled", true);
                boolean bridge = api.forms().isAvailable();
                Map<String, String> diagnostics = api.forms().diagnostics(null);
                var stats = api.forms().stats();
                int warnSessions = Math.max(1, plugin.getConfig().getInt("forms.diagnostics.warn-active-sessions", 200));
                long warnFailedOpens = Math.max(0L, plugin.getConfig().getLong("forms.diagnostics.warn-failed-opens", 1L));
                long warnInvalid = Math.max(0L, plugin.getConfig().getLong("forms.diagnostics.warn-invalid-responses", 10L));
                rows.add(new DoctorRow(!enabled || bridge ? Severity.OK : Severity.WARN, "forms.enabled", String.valueOf(enabled), enabled ? "Bedrock form service enabled" : "Bedrock form service disabled by config"));
                rows.add(new DoctorRow(!enabled || Boolean.parseBoolean(diagnostics.getOrDefault("floodgate-plugin", "false")) ? Severity.OK : Severity.WARN, "forms.floodgate", diagnostics.getOrDefault("floodgate-plugin", "false"), diagnostics.getOrDefault("status", "unknown")));
                rows.add(new DoctorRow(!enabled || bridge ? Severity.OK : Severity.WARN, "forms.bridge", String.valueOf(bridge), diagnostics.getOrDefault("status", "unknown")));
                rows.add(new DoctorRow(stats.sessions() > warnSessions ? Severity.WARN : Severity.OK, "forms.sessions", String.valueOf(stats.sessions()), "Active Bedrock form sessions"));
                rows.add(new DoctorRow(stats.invalidResponses() > warnInvalid ? Severity.WARN : Severity.OK, "forms.invalid-responses", String.valueOf(stats.invalidResponses()), "Rejected/invalid Bedrock form responses since startup"));
                rows.add(new DoctorRow(Severity.OK, "forms.dialog-routing", String.valueOf(plugin.getConfig().getBoolean("forms.dialog-routing.enabled", true)), "Bedrock players using platform.dialogs() are routed to Cumulus forms when available"));
                rows.add(new DoctorRow(Severity.OK, "forms.security", "ttl=" + plugin.getConfig().getLong("forms.session-ttl-seconds", 180L) + "s", "Session id, expiry, and session-scoped one-time response guards"));
                rows.add(new DoctorRow(stats.failedOpens() >= warnFailedOpens && stats.failedOpens() > 0 ? Severity.WARN : Severity.OK, "forms.failed-opens", String.valueOf(stats.failedOpens()), "Bedrock form opens that failed since startup"));
                rows.add(new DoctorRow(Severity.OK, "forms.counters", "opened=" + stats.opened() + ", submitted=" + stats.submitted() + ", closed=" + stats.closed() + ", expired=" + stats.expired(), "Runtime Bedrock form counters"));
            }
            case "serverlinks", "server-links", "links" -> {
                boolean enabled = api.serverLinks().enabled();
                boolean available = api.serverLinks().available();
                rows.add(new DoctorRow(enabled ? Severity.OK : Severity.WARN, "server-links.enabled", String.valueOf(enabled), "Config master toggle for ESC Server Links"));
                rows.add(new DoctorRow(available ? Severity.OK : Severity.WARN, "server-links.api", available ? "available" : "unavailable", api.serverLinks().status()));
                rows.add(new DoctorRow(Severity.OK, "server-links.entries", String.valueOf(api.serverLinks().configuredCount()), "Configured URL links sent to compatible clients"));
                rows.add(new DoctorRow(Severity.OK, "server-links.sent", String.valueOf(api.serverLinks().sentCount()), "Number of player ServerLinks payloads sent since startup"));
            }
            case "audit" -> {
                boolean auditEnabled = plugin.getConfig().getBoolean("audit.enabled", false);
                boolean auditWriteEnabled = plugin.getConfig().getBoolean("audit.write-enabled", false);
                boolean ledgerWriteEnabled = plugin.getConfig().getBoolean("ledger.write-enabled", false);
                rows.add(new DoctorRow(Severity.OK, "audit.enabled", String.valueOf(auditEnabled), "Audit master toggle; false is recommended for busy production unless tracing incidents."));
                rows.add(new DoctorRow(Severity.OK, "audit.write-enabled", String.valueOf(auditWriteEnabled), "Audit write path toggle."));
                rows.add(new DoctorRow(Severity.OK, "ledger.write-enabled", String.valueOf(ledgerWriteEnabled), "Ledger write path toggle."));
                rows.add(new DoctorRow(Severity.OK, "audit.storage", api.database().mode().name(), "Audit ledger uses SQL when healthy, YAML fallback otherwise"));
            }
            default -> rows.add(new DoctorRow(Severity.WARN, "doctor.category", key, "Unknown doctor category"));
        }
        return rows;
    }

    @Override
    public void reload() {
        // stateless
    }

    private boolean platformDatabaseRequested(Plugin target) {
        if (target == null) {
            return false;
        }
        org.bukkit.configuration.file.FileConfiguration config = target.getConfig();
        return config.getBoolean("suite.database.platform.enabled", false)
                || "platform".equalsIgnoreCase(config.getString("suite.database.mode", "local"))
                || "platform".equalsIgnoreCase(config.getString("ledat-suite.database.mode", "local"))
                || "platform".equalsIgnoreCase(config.getString("database.type", ""))
                || "platform".equalsIgnoreCase(config.getString("storage.type", ""))
                || config.getBoolean("storage.platform.enabled", false);
    }

    private boolean platformDatabaseRequired(Plugin target) {
        if (target == null) {
            return false;
        }
        org.bukkit.configuration.file.FileConfiguration config = target.getConfig();
        return config.getBoolean("suite.database.platform.require-platform", false)
                || ("platform".equalsIgnoreCase(config.getString("ledat-suite.database.mode", "local"))
                && config.getBoolean("ledat-suite.database.platform.require-platform", false))
                || config.getBoolean("storage.platform.require-platform", false);
    }

    private boolean effectiveMainThreadDbReject() {
        boolean production = plugin.getConfig().getBoolean("production-profile.enabled", false);
        return plugin.getConfig().getBoolean("storage.safety.reject-main-thread-borrows", false)
                || (production && plugin.getConfig().getBoolean("production-profile.database.reject-main-thread-borrows", true));
    }

    private boolean foliaSupportedClaim() {
        return foliaSupportedClaim(plugin);
    }

    private boolean foliaSupportedClaim(Plugin target) {
        try {
            Object value = target.getDescription().getClass().getMethod("isFoliaSupported").invoke(target.getDescription());
            return Boolean.TRUE.equals(value);
        } catch (Throwable ignored) {
            return target.getDescription().getName().equalsIgnoreCase("LeDatPlatform");
        }
    }

    private int declaredCommandCount(Plugin target) {
        if (target == null || target.getDescription() == null) {
            return 0;
        }
        try {
            Map<?, ?> commands = target.getDescription().getCommands();
            return commands == null ? 0 : commands.size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private String normalize(String value) {
        return value == null ? "unknown" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private String safe(String value) {
        return value == null ? "" : value.replaceAll("(?i)(password|token|secret|apikey|api-key)=([^,\\s]+)", "$1=***");
    }
}
