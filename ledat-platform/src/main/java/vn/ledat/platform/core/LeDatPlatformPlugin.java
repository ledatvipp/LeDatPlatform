package vn.ledat.platform.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.platform.api.LeDatPlatformApi;
import vn.ledat.platform.api.LeDatPlatformProvider;
import vn.ledat.platform.api.scheduler.LeDatTask;
import vn.ledat.platform.core.api.DefaultLeDatPlatformApi;
import vn.ledat.platform.core.audit.DefaultAuditLedger;
import vn.ledat.platform.core.bedrockform.DefaultBedrockFormService;
import vn.ledat.platform.core.block.DefaultPlayerBlockTracker;
import vn.ledat.platform.core.cache.DefaultUserCacheService;
import vn.ledat.platform.core.command.DefaultCommandService;
import vn.ledat.platform.core.command.PlatformAdminCommands;
import vn.ledat.platform.core.config.DefaultConfigService;
import vn.ledat.platform.core.cooldown.DefaultCooldownService;
import vn.ledat.platform.core.cooldown.DefaultRateLimitService;
import vn.ledat.platform.core.database.DefaultDatabaseProvider;
import vn.ledat.platform.core.database.SharedDatabaseCompatibilityBridge;
import vn.ledat.platform.core.dialog.DefaultDialogService;
import vn.ledat.platform.core.doctor.DefaultDoctorService;
import vn.ledat.platform.core.executor.DefaultManagedExecutorService;
import vn.ledat.platform.core.economy.DefaultEconomyService;
import vn.ledat.platform.core.gui.DefaultGuiService;
import vn.ledat.platform.core.guard.DefaultPlatformGuardService;
import vn.ledat.platform.core.hook.DefaultHookRegistry;
import vn.ledat.platform.core.item.DefaultCustomItemService;
import vn.ledat.platform.core.integration.BuiltInIntegrations;
import vn.ledat.platform.core.language.DefaultLanguageService;
import vn.ledat.platform.core.ledger.DefaultLedgerService;
import vn.ledat.platform.core.lifecycle.DefaultOwnerLifecycleService;
import vn.ledat.platform.core.logging.DefaultPlatformLogger;
import vn.ledat.platform.core.placeholder.DefaultPlaceholderRouter;
import vn.ledat.platform.core.profile.DefaultGameProfileService;
import vn.ledat.platform.core.scheduler.DefaultScheduler;
import vn.ledat.platform.core.serverlink.DefaultServerLinkService;
import vn.ledat.platform.core.sound.DefaultSoundService;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class LeDatPlatformPlugin extends JavaPlugin {
    public static final int CONFIG_VERSION = 25;
    public static final int LANGUAGE_VERSION = 5;

    private DefaultScheduler scheduler;
    private DefaultPlatformLogger logger;
    private DefaultConfigService configs;
    private DefaultLanguageService languages;
    private DefaultDatabaseProvider database;
    private DefaultCooldownService cooldowns;
    private DefaultRateLimitService rateLimits;
    private DefaultCommandService commands;
    private DefaultPlaceholderRouter placeholders;
    private DefaultAuditLedger audit;
    private DefaultLedgerService ledger;
    private DefaultGuiService guis;
    private DefaultDialogService dialogs;
    private DefaultBedrockFormService forms;
    private DefaultSoundService sounds;
    private DefaultServerLinkService serverLinks;
    private DefaultHookRegistry hooks;
    private DefaultDoctorService doctor;
    private DefaultPlatformGuardService guards;
    private DefaultManagedExecutorService executors;
    private DefaultOwnerLifecycleService lifecycle;
    private DefaultUserCacheService userCaches;
    private DefaultCustomItemService items;
    private DefaultGameProfileService profiles;
    private DefaultPlayerBlockTracker playerBlocks;
    private DefaultEconomyService economy;
    private SharedDatabaseCompatibilityBridge sharedDatabaseBridge;
    private DefaultLeDatPlatformApi api;
    private LeDatTask healthTask = LeDatTask.NOOP;
    private LeDatTask cleanupTask = LeDatTask.NOOP;
    private final AtomicBoolean healthRunning = new AtomicBoolean(false);
    private final AtomicBoolean cleanupRunning = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        long bootStartedAt = System.nanoTime();
        saveDefaults();
        this.scheduler = new DefaultScheduler(this);
        this.logger = new DefaultPlatformLogger(getConfig().getBoolean("debug.enabled", false));
        this.configs = new DefaultConfigService(this);
        configs.migrateBundledYaml(this, "config.yml", "config-version", CONFIG_VERSION);
        configs.migrateBundledYaml(this, "languages/vi_VN.yml", "language-version", LANGUAGE_VERSION);
        configs.migrateBundledYaml(this, "languages/en_US.yml", "language-version", LANGUAGE_VERSION);
        configs.migrateBundledYaml(this, "languages/zh_CN.yml", "language-version", LANGUAGE_VERSION);
        reloadConfig();
        scheduler.reloadSettings();
        logConfigReport("startup", configs.validatePlatformConfig());

        this.languages = new DefaultLanguageService(this, scheduler);
        this.database = new DefaultDatabaseProvider(this);
        this.userCaches = new DefaultUserCacheService(this, scheduler);
        this.cooldowns = new DefaultCooldownService();
        this.rateLimits = new DefaultRateLimitService();
        this.commands = new DefaultCommandService(this, languages, cooldowns, rateLimits, scheduler);
        this.placeholders = new DefaultPlaceholderRouter();
        this.audit = new DefaultAuditLedger(this, database, getLogger());
        this.sharedDatabaseBridge = new SharedDatabaseCompatibilityBridge(this, database, audit);
        this.sharedDatabaseBridge.publish();
        this.ledger = new DefaultLedgerService(this, database, audit, getLogger());
        this.sounds = new DefaultSoundService(this, scheduler);
        this.profiles = new DefaultGameProfileService(this);
        this.guis = new DefaultGuiService(this, languages, scheduler, sounds, profiles);
        this.forms = new DefaultBedrockFormService(this, languages, scheduler);
        this.dialogs = new DefaultDialogService(this, guis, languages, scheduler, sounds, forms);
        this.serverLinks = new DefaultServerLinkService(this, scheduler);
        this.hooks = new DefaultHookRegistry();
        this.items = new DefaultCustomItemService(this);
        this.playerBlocks = new DefaultPlayerBlockTracker(this);
        this.economy = new DefaultEconomyService();
        AtomicReference<LeDatPlatformApi> apiRef = new AtomicReference<>();
        this.executors = new DefaultManagedExecutorService(this);
        this.guards = new DefaultPlatformGuardService(this, apiRef::get);
        this.doctor = new DefaultDoctorService(this, apiRef::get, () -> lifecycle);
        this.api = new DefaultLeDatPlatformApi(
                getDescription().getVersion(), scheduler, logger, languages, configs, database, cooldowns,
                rateLimits, commands, placeholders, audit, ledger, guis, dialogs, forms, sounds, serverLinks, hooks, doctor, guards, executors,
                userCaches, items, profiles, playerBlocks, economy
        );
        apiRef.set(api);
        this.lifecycle = new DefaultOwnerLifecycleService(this, apiRef::get);

        registerApi();
        registerBuiltInRoutes();
        BuiltInIntegrations.install(this, api);
        hooks.reload();
        new PlatformAdminCommands(api, this::reloadPlatform).register(this, commands);
        startTasks();
        logStartupBanner(bootStartedAt);
    }

    @Override
    public void onDisable() {
        healthTask.cancelQuietly();
        cleanupTask.cancelQuietly();
        if (api != null) {
            BuiltInIntegrations.uninstall(this, api);
        }
        if (lifecycle != null) {
            lifecycle.cleanupAllLoaded("platform-disable");
            lifecycle.unregister();
        }
        if (ledger != null) {
            ledger.flush();
        }
        if (audit != null) {
            audit.flush();
        }
        if (userCaches != null) {
            try {
                userCaches.flushAll().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Throwable error) {
                getLogger().warning("User cache shutdown flush failed: " + error.getMessage());
            }
            userCaches.cleanup(this);
        }
        if (profiles != null) {
            profiles.clear();
        }
        if (dialogs != null) {
            dialogs.cleanup(this);
        }
        if (forms != null) {
            forms.cleanupAll();
        }
        if (guis != null) {
            guis.cleanup(this);
        }
        if (sounds != null) {
            sounds.reload();
        }
        if (languages != null) {
            languages.shutdown();
            languages.unregisterPlugin(this);
        }
        if (commands != null) {
            commands.unregisterPlugin(this);
        }
        if (placeholders != null) {
            placeholders.unregisterPlugin(this);
        }
        if (items != null) {
            items.unregister(this);
        }
        if (executors != null) {
            executors.shutdownAll();
        }
        if (sharedDatabaseBridge != null) {
            sharedDatabaseBridge.unpublish();
        }
        if (database != null) {
            database.close();
        }
        if (scheduler != null) {
            scheduler.cancelAll(this);
        }
        if (api != null) {
            Bukkit.getServicesManager().unregister(LeDatPlatformApi.class, api);
            LeDatPlatformProvider.clear(api);
        }
        getLogger().info("LeDatPlatform core disabled.");
    }

    public void reloadPlatform() {
        if (configs != null) {
            configs.migrateBundledYaml(this, "config.yml", "config-version", CONFIG_VERSION);
            configs.migrateBundledYaml(this, "languages/vi_VN.yml", "language-version", LANGUAGE_VERSION);
            configs.migrateBundledYaml(this, "languages/en_US.yml", "language-version", LANGUAGE_VERSION);
            configs.migrateBundledYaml(this, "languages/zh_CN.yml", "language-version", LANGUAGE_VERSION);
        }
        reloadConfig();
        if (scheduler != null) {
            scheduler.reloadSettings();
        }
        if (playerBlocks != null) {
            playerBlocks.reload();
        }
        if (logger != null) {
            logger.setDebugEnabled(getConfig().getBoolean("debug.enabled", false));
        }
        if (languages != null) {
            languages.reload();
        }
        if (configs != null) {
            logConfigReport("reload", configs.validatePlatformConfig());
        }
        if (database != null) {
            database.reload();
        }
        if (sharedDatabaseBridge != null) {
            sharedDatabaseBridge.publish();
        }
        if (hooks != null) {
            scheduler.sync(this, hooks::reload);
        }
        if (api != null) {
            BuiltInIntegrations.install(this, api);
        }
        if (placeholders != null) {
            placeholders.reload();
        }
        if (audit != null) {
            audit.reload();
        }
        if (ledger != null) {
            ledger.reload();
        }
        if (dialogs != null) {
            dialogs.reload();
        }
        if (forms != null) {
            forms.reload();
        }
        if (sounds != null) {
            sounds.reload();
        }
        if (serverLinks != null) {
            serverLinks.reload();
        }
        if (doctor != null) {
            doctor.reload();
        }
        restartTasks();
    }

    public LeDatPlatformApi api() {
        return api;
    }

    private void saveDefaults() {
        saveResourceIfMissing("config.yml");
        saveResourceIfMissing("languages/vi_VN.yml");
        saveResourceIfMissing("languages/en_US.yml");
        saveResourceIfMissing("languages/zh_CN.yml");
    }

    /**
     * Prints a concise startup summary only after every core service is ready.
     * Keeping every line as a separate log record lets Paper/Folia preserve its
     * usual timestamp, level and plugin prefix in the console.
     */
    private void logStartupBanner(long bootStartedAt) {
        long bootMillis = Math.max(0L, (System.nanoTime() - bootStartedAt) / 1_000_000L);
        String version = getDescription().getVersion();
        String displayVersion = version.startsWith("v") ? version : "v" + version;
        String runtime = scheduler.isFolia() ? "Folia" : "Paper / Spigot";
        String storage = database.mode().name() + " (" + database.storageUnits().size() + " unit(s))";

        List<String> lines = List.of(
                "",
                "    _      _____ ____    _  _____   ____  _        _  _____ _____ ___  ____  __  __",
                "   | |    | ____|  _ \\  / \\|_   _| |  _ \\| |      / \\|_   _|  ___/ _ \\|  _ \\|  \\/  |",
                "   | |    |  _| | | | |/ _ \\ | |   | |_) | |     / _ \\ | | | |_ | | | | |_) | |\\/| |",
                "   | |___ | |___| |_| / ___ \\| |   |  __/| |___ / ___ \\| | |  _|| |_| |  _ <| |  | |",
                "   |_____||_____|____/_/   \\_\\|_|   |_|   |_____/_/   \\_\\|_| |_|   \\___/|_| \\_|",
                "   [CORE] LeDatPlatform - Shared runtime for LeDat plugins",
                "",
                "   --------------------------------------------------------------",
                "   Version   | " + displayVersion + " (API " + api.apiVersion() + ")",
                "   Runtime   | " + runtime + " / " + scheduler.implementationName(),
                "   Storage   | " + storage,
                "   Boot      | " + bootMillis + "ms",
                "   --------------------------------------------------------------",
                "   Modules:",
                "    |- Scheduler (Paper + Folia)",
                "    |- Config & Language (YAML migration, validation, i18n)",
                "    |- Commands & Placeholders (routes, cooldown, rate-limit)",
                "    |- GUI, Dialogs & Bedrock Forms",
                "    |- Database (YAML, SQLite, MySQL, migrations)",
                "    |- Audit, Ledger & Platform Doctor",
                "    |- Sound, Server Links & Hook Registry",
                "    |- Owner Lifecycle, Guard & Managed Executors",
                "    `- Built-in integrations (PlaceholderAPI, Vault, LuckPerms, ItemsAdder, Oraxen, Nexo, MMOItems, PlayerPoints, Floodgate)",
                "   --------------------------------------------------------------"
        );
        lines.forEach(getLogger()::info);
    }

    private void saveResourceIfMissing(String path) {
        java.io.File file = new java.io.File(getDataFolder(), path);
        if (!file.exists()) {
            java.io.File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            saveResource(path, false);
        }
    }

    private void logConfigReport(String stage, vn.ledat.platform.api.config.ConfigValidationReport report) {
        if (report == null) {
            return;
        }
        for (String warning : report.warnings()) {
            getLogger().warning("[" + stage + "] " + warning);
        }
        for (String error : report.errors()) {
            getLogger().severe("[" + stage + "] " + error);
        }
    }

    private void registerApi() {
        Bukkit.getServicesManager().register(LeDatPlatformApi.class, api, this, ServicePriority.Highest);
        LeDatPlatformProvider.setInstance(api);
    }

    private void registerBuiltInRoutes() {
        placeholders.registerRoute(this, "platform", (player, parameter) -> switch (parameter) {
            case "version" -> getDescription().getVersion();
            case "api_version" -> api.apiVersion();
            case "scheduler" -> api.scheduler().implementationName().toLowerCase(java.util.Locale.ROOT);
            case "storage" -> api.database().mode().name().toLowerCase(java.util.Locale.ROOT);
            case "storage_units" -> String.valueOf(api.database().storageUnits().size());
            case "storage_yaml_units" -> String.valueOf(api.database().stats().yamlStores());
            case "storage_sqlite_units" -> String.valueOf(api.database().stats().sqliteStores());
            case "commands", "command_nodes" -> String.valueOf(api.commands().stats().commandNodes());
            case "command_roots" -> String.valueOf(api.commands().stats().rootCommands());
            case "command_legacy" -> String.valueOf(api.commands().stats().legacyDelegates());
            case "command_missing", "command_unregistered" -> String.valueOf(api.commands().stats().missingPluginYml());
            case "status" -> api.database().healthy() ? "healthy" : "degraded";
            case "dialogs" -> api.dialogs().nativeDialogsAvailable() ? "native" : (api.dialogs().fallbackEnabled() ? "fallback" : "disabled");
            case "dialog_sessions" -> String.valueOf(api.dialogs().sessionCount());
            case "dialog_native" -> String.valueOf(api.dialogs().nativeDialogsAvailable());
            case "dialog_templates" -> String.valueOf(api.dialogs().templateCount(this));
            case "dialog_overrides" -> String.valueOf(api.dialogs().displayModeOverrides().size());
            case "forms" -> api.forms().isAvailable() ? "available" : "unavailable";
            case "forms_sessions", "form_sessions" -> String.valueOf(api.forms().sessionCount());
            case "forms_available", "form_available" -> String.valueOf(api.forms().isAvailable());
            case "forms_bridge", "form_bridge" -> api.forms().diagnostics(null).getOrDefault("bridge", "false");
            case "server_links" -> api.serverLinks().enabled() && api.serverLinks().available() ? "enabled" : "disabled";
            case "server_link_entries" -> String.valueOf(api.serverLinks().configuredCount());
            case "server_link_sent" -> String.valueOf(api.serverLinks().sentCount());
            case "production_profile" -> String.valueOf(api.guards().productionProfileEnabled());
            case "guard_unregistered_threads" -> String.valueOf(api.guards().snapshot().unregisteredLeDatThreads());
            case "guard_disabled_task_owners" -> String.valueOf(api.guards().snapshot().disabledPluginTaskOwners());
            case "lifecycle_cleanups" -> lifecycle == null ? "0" : String.valueOf(lifecycle.stats().cleanupRuns());
            case "lifecycle_failures" -> lifecycle == null ? "0" : String.valueOf(lifecycle.stats().cleanupFailures());
            case "user_caches" -> String.valueOf(api.userCaches().stats().caches());
            case "user_cache_entries" -> String.valueOf(api.userCaches().stats().entries());
            case "item_adapters" -> String.valueOf(api.items().stats().availableAdapters());
            case "profile_cache_entries" -> String.valueOf(api.profiles().stats().entries());
            case "player_blocks_marked" -> String.valueOf(api.playerBlocks().stats().marks());
            case "economy_providers" -> String.valueOf(api.economy().providers().size());
            default -> "LeDatPlatform";
        }, "LeDatPlatform core metadata");
    }

    private void startTasks() {
        long healthDelayMs = Math.max(5000L, getConfig().getLong("storage.health.startup-delay-ms", 30000L));
        long healthIntervalMs = Math.max(5000L, getConfig().getLong("storage.health.interval-ms", 30000L));
        healthTask = scheduler.asyncRepeat(this, "storage-health", () -> {
            if (!healthRunning.compareAndSet(false, true)) {
                return;
            }
            try {
                if (database != null) {
                    database.healthCheck();
                }
                if (guards != null) {
                    guards.warnProductionRisks();
                }
            } finally {
                healthRunning.set(false);
            }
        }, Duration.ofMillis(healthDelayMs), Duration.ofMillis(healthIntervalMs));
        long cleanupDelaySeconds = Math.max(30L, getConfig().getLong("cooldown.cleanup-startup-delay-seconds", 90L));
        long cleanupIntervalSeconds = Math.max(30L, getConfig().getLong("cooldown.cleanup-interval-seconds", 120L));
        cleanupTask = scheduler.asyncRepeat(this, "cooldown-cleanup", () -> {
            if (!cleanupRunning.compareAndSet(false, true)) {
                return;
            }
            try {
                int removedCooldowns = cooldowns.cleanupExpired();
                int removedRateLimits = rateLimits.cleanupExpired();
                int removedDialogs = dialogs == null ? 0 : dialogs.cleanupExpired();
                int removedForms = forms == null ? 0 : forms.cleanupExpired();
                int removedSounds = sounds == null ? 0 : sounds.cleanupExpiredCooldowns();
                if (logger.debugEnabled()) {
                    logger.debug(this, "Cleanup removed cooldowns=" + removedCooldowns + ", rate-limits=" + removedRateLimits + ", dialogs=" + removedDialogs + ", forms=" + removedForms + ", sounds=" + removedSounds);
                }
            } finally {
                cleanupRunning.set(false);
            }
        }, Duration.ofSeconds(cleanupDelaySeconds), Duration.ofSeconds(cleanupIntervalSeconds));
    }

    private void restartTasks() {
        healthTask.cancelQuietly();
        cleanupTask.cancelQuietly();
        if (scheduler != null) {
            startTasks();
        }
    }
}
