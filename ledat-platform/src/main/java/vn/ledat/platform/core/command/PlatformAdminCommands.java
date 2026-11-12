package vn.ledat.platform.core.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import vn.ledat.platform.api.LeDatPlatformApi;
import vn.ledat.platform.api.command.CommandArgumentType;
import vn.ledat.platform.api.command.CommandContext;
import vn.ledat.platform.api.command.CommandRoute;
import vn.ledat.platform.api.command.CommandService;
import vn.ledat.platform.api.command.CommandStats;
import vn.ledat.platform.api.doctor.DoctorRow;
import vn.ledat.platform.api.doctor.DoctorSnapshot;
import vn.ledat.platform.api.guard.GuardSnapshot;
import vn.ledat.platform.api.scheduler.LeDatScheduler;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class PlatformAdminCommands {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final List<String> DOCTOR_CATEGORIES = List.of("all", "core", "production", "suite", "plugins", "integration", "scheduler", "tasks", "storage", "language", "config", "commands", "hooks", "placeholder", "gui", "dialogs", "forms", "serverlinks", "audit");
    private static final List<String> STRICT_MODES = List.of("--strict", "strict");
    private static final List<String> VERIFY_MODES = List.of("--deep", "deep");
    private final LeDatPlatformApi api;
    private final Runnable reloadAction;

    public PlatformAdminCommands(LeDatPlatformApi api, Runnable reloadAction) {
        this.api = api;
        this.reloadAction = reloadAction;
    }

    public void register(org.bukkit.plugin.Plugin plugin, CommandService commands) {
        commands.command(plugin, "ledatplatform")
                .permission("ledatplatform.admin")
                .usage("/ledatplatform <status|doctor|scheduler|commands|storage|caches|items|profiles|blocks|economy|hooks|gui|dialogs|forms|reload>")
                .description("LeDatPlatform core command")
                .maxArgs(0)
                .rateLimit(8, Duration.ofSeconds(3))
                .executor(this::status)
                .tab(context -> List.of("status", "doctor", "guard", "suite", "plugins", "integration", "scheduler", "commands", "storage", "db", "caches", "items", "profiles", "blocks", "economy", "hooks", "placeholders", "language", "gui", "dialogs", "forms", "links", "audit", "reload", "debug"))
                .subCommand("status", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(this::status))
                .subCommand("doctor", sub -> sub.permission("ledatplatform.admin")
                        .usage("/ledatplatform doctor [category] [--strict]")
                        .maxArgs(2)
                        .optionalChoicesArgument("category", DOCTOR_CATEGORIES)
                        .optionalChoicesArgument("mode", STRICT_MODES)
                        .executor(this::doctor)
                        .tab(this::doctorTabs))
                .subCommand("guard", sub -> sub.permission("ledatplatform.admin")
                        .maxArgs(0)
                        .executor(this::guard)
                        .tab(context -> guardTabs(context.length() <= 1 ? context.arg(0, "") : context.arg(1, "")))
                        .subCommand("status", child -> child.permission("ledatplatform.admin").maxArgs(0).executor(this::guard)))
                .subCommand("suite", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(context -> doctor(new CommandContext(context.plugin(), context.sender(), context.label(), new String[]{"suite"}))))
                .subCommand("plugins", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(context -> doctor(new CommandContext(context.plugin(), context.sender(), context.label(), new String[]{"suite"}))))
                .subCommand("integration", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(context -> doctor(new CommandContext(context.plugin(), context.sender(), context.label(), new String[]{"suite"}))))
                .subCommand("scheduler", sub -> sub.permission("ledatplatform.admin")
                        .maxArgs(0)
                        .executor(this::scheduler)
                        .tab(context -> List.of("status", "owners", "doctor"))
                        .subCommand("status", child -> child.permission("ledatplatform.admin").maxArgs(0).executor(this::scheduler))
                        .subCommand("owners", child -> child.permission("ledatplatform.admin").maxArgs(0).executor(this::schedulerOwners))
                        .subCommand("doctor", child -> child.permission("ledatplatform.admin").maxArgs(0).executor(context -> doctor(new CommandContext(context.plugin(), context.sender(), context.label(), new String[]{"scheduler"})))))
                .subCommand("commands", sub -> sub.permission("ledatplatform.admin")
                        .maxArgs(0)
                        .executor(this::commands)
                        .tab(context -> commandTabs(context.length() <= 1 ? context.arg(0, "") : context.arg(1, "")))
                        .subCommand("stats", child -> child.permission("ledatplatform.admin").maxArgs(0).executor(this::commands))
                        .subCommand("list", child -> child.permission("ledatplatform.admin").maxArgs(1).optionalArgument("plugin", CommandArgumentType.PLUGIN_NAME).executor(this::commandList).tab(context -> pluginTabs(context.arg(0, ""))))
                        .subCommand("legacy", child -> child.permission("ledatplatform.admin").maxArgs(1).optionalArgument("plugin", CommandArgumentType.PLUGIN_NAME).executor(this::commandLegacy).tab(context -> pluginTabs(context.arg(0, ""))))
                        .subCommand("missing", child -> child.permission("ledatplatform.admin").maxArgs(1).optionalArgument("plugin", CommandArgumentType.PLUGIN_NAME).executor(this::commandMissing).tab(context -> pluginTabs(context.arg(0, ""))))
                        .subCommand("plugin", child -> child.permission("ledatplatform.admin").exactArgs(1).argument("plugin", CommandArgumentType.PLUGIN_NAME).executor(this::commandPlugin).tab(context -> pluginTabs(context.arg(0, "")))))
                .subCommand("storage", sub -> sub.permission("ledatplatform.admin")
                        .maxArgs(0)
                        .executor(this::storage)
                        .tab(context -> storageTabs(context.length() <= 1 ? context.arg(0, "") : context.arg(1, "")))
                        .subCommand("status", child -> child.permission("ledatplatform.admin").maxArgs(0).executor(this::storage))
                        .subCommand("list", child -> child.permission("ledatplatform.admin").maxArgs(1).optionalArgument("plugin", CommandArgumentType.PLUGIN_NAME).executor(this::storageList).tab(context -> pluginTabs(context.arg(0, ""))))
                        .subCommand("path", child -> child.permission("ledatplatform.admin").maxArgs(1).optionalArgument("plugin", CommandArgumentType.PLUGIN_NAME).executor(this::storagePath).tab(context -> pluginTabs(context.arg(0, ""))))
                        .subCommand("flush", child -> child.permission("ledatplatform.admin").maxArgs(1).optionalArgument("plugin", CommandArgumentType.PLUGIN_NAME).executor(this::storageFlush).tab(context -> pluginTabs(context.arg(0, ""))))
                        .subCommand("checkpoint", child -> child.permission("ledatplatform.admin").maxArgs(1).optionalArgument("plugin", CommandArgumentType.PLUGIN_NAME).executor(this::storageCheckpoint).tab(context -> pluginTabs(context.arg(0, ""))))
                        .subCommand("verify", child -> child.permission("ledatplatform.admin").maxArgs(2).optionalArgument("plugin", CommandArgumentType.PLUGIN_NAME).optionalChoicesArgument("mode", VERIFY_MODES).executor(this::storageVerify).tab(this::storageVerifyTabs))
                        .subCommand("health", child -> child.permission("ledatplatform.admin").maxArgs(0).executor(this::storageHealth))
                        .subCommand("close", child -> child.permission("ledatplatform.admin").exactArgs(1).argument("plugin", CommandArgumentType.PLUGIN_NAME).executor(this::storageClose).tab(context -> pluginTabs(context.arg(0, "")))))
                .subCommand("db", sub -> sub.permission("ledatplatform.admin")
                        .maxArgs(0)
                        .executor(this::storage)
                        .tab(context -> storageTabs(context.arg(0, ""))))
                .subCommand("caches", sub -> sub.permission("ledatplatform.admin").aliases("cache", "usercache").maxArgs(0).executor(this::caches))
                .subCommand("items", sub -> sub.permission("ledatplatform.admin").aliases("customitems", "customitem").maxArgs(0).executor(this::items))
                .subCommand("profiles", sub -> sub.permission("ledatplatform.admin").aliases("profilecache").maxArgs(0).executor(this::profiles))
                .subCommand("blocks", sub -> sub.permission("ledatplatform.admin").aliases("blocktracker").maxArgs(0).executor(this::blocks))
                .subCommand("economy", sub -> sub.permission("ledatplatform.admin").aliases("currencies").maxArgs(0).executor(this::economy))
                .subCommand("hooks", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(this::hooks))
                .subCommand("placeholders", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(this::placeholders))
                .subCommand("language", sub -> sub.permission("ledatplatform.admin")
                        .usage("/ledatplatform language [set <player> <locale>|clear <player>|reload [all|plugin]]")
                        .maxArgs(3)
                        .executor(this::language)
                        .tab(this::languageTabs))
                .subCommand("gui", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(this::gui))
                .subCommand("dialogs", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(this::dialogs))
                .subCommand("dialog", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(this::dialogs))
                .subCommand("forms", sub -> sub.permission("ledatplatform.admin")
                        .maxArgs(0)
                        .executor(this::forms)
                        .tab(this::formsTabs)
                        .subCommand("status", child -> child.permission("ledatplatform.admin").maxArgs(0).executor(this::forms))
                        .subCommand("player", child -> child.permission("ledatplatform.admin").exactArgs(1).argument("player", CommandArgumentType.PLAYER_NAME).executor(this::formsPlayer).tab(context -> onlinePlayerTabs(context.arg(0, ""))))
                        .subCommand("test", child -> child.permission("ledatplatform.admin").exactArgs(1).argument("player", CommandArgumentType.PLAYER_NAME).executor(this::formsTest).tab(context -> onlinePlayerTabs(context.arg(0, "")))))
                .subCommand("form", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(this::forms).tab(this::formsTabs))
                .subCommand("links", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(this::links))
                .subCommand("serverlinks", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(this::links))
                .subCommand("server-links", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(this::links))
                .subCommand("audit", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(this::audit))
                .subCommand("debug", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(this::debug))
                .subCommand("reload", sub -> sub.permission("ledatplatform.admin").maxArgs(0).executor(context -> {
                    send(context.sender(), "<yellow>Đang reload LeDatPlatform core async...");
                    api.scheduler().async(context.plugin(), "platform-reload", () -> {
                        reloadAction.run();
                        api.scheduler().sender(context.plugin(), context.sender(), () -> send(context.sender(), "<green>Đã reload LeDatPlatform core."));
                    });
                }))
                .register();
    }

    private void status(CommandContext context) {
        send(context.sender(), "<dark_gray><st>--------------------------------------------------</st>");
        send(context.sender(), "<light_purple><bold>LeDatPlatform</bold> <gray>| API <white>" + api.apiVersion() + " <gray>| Runtime <white>" + api.platformVersion());
        send(context.sender(), "<gray>Scheduler: <white>" + api.scheduler().implementationName() + " <dark_gray>| <gray>Storage: <white>" + api.database().mode() + " <dark_gray>| <gray>Healthy: <white>" + api.database().healthy());
        CommandStats commandStats = api.commands().stats();
        GuardSnapshot guard = api.guards().snapshot();
        send(context.sender(), "<gray>Routes: <white>" + api.placeholders().routes().size() + " <dark_gray>| <gray>Hooks: <white>" + api.hooks().statuses().size() + " <dark_gray>| <gray>Dialogs: <white>" + (api.dialogs().nativeDialogsAvailable() ? "native" : (api.dialogs().fallbackEnabled() ? "fallback" : "disabled")) + " <dark_gray>| <gray>Links: <white>" + api.serverLinks().configuredCount());
        send(context.sender(), "<gray>Commands: roots=<white>" + commandStats.rootCommands() + " <gray>nodes=<white>" + commandStats.commandNodes() + " <gray>legacy=<yellow>" + commandStats.legacyDelegates() + " <gray>unregistered=<white>" + commandStats.missingPluginYml() + " <gray>slow=<white>" + commandStats.slowHandlers());
        send(context.sender(), "<gray>Guards: production=<white>" + guard.productionProfileEnabled() + " <gray>managedExecutors=<white>" + guard.managedExecutorCount() + " <gray>rawThreads=<white>" + guard.unregisteredLeDatThreads() + " <gray>disabledTaskOwners=<white>" + guard.disabledPluginTaskOwners());
        send(context.sender(), "<gray>Shared: caches=<white>" + api.userCaches().stats().caches() + " <gray>cacheEntries=<white>" + api.userCaches().stats().entries()
                + " <gray>itemAdapters=<white>" + api.items().stats().availableAdapters() + " <gray>economies=<white>" + api.economy().providers().size());
        send(context.sender(), "<gray>Dùng <white>/ledatplatform doctor all</white> hoặc <white>/ledatplatform suite</white> để xem chi tiết.");
        send(context.sender(), "<dark_gray><st>--------------------------------------------------</st>");
    }

    private void doctor(CommandContext context) {
        DoctorRequest request = doctorRequest(context);
        String category = request.category();
        if (category.equals("all")) {
            DoctorSnapshot snapshot = api.doctor().snapshot();
            send(context.sender(), "<dark_gray><st>--------------------------------------------------</st>");
            send(context.sender(), "<light_purple>Doctor <gray>| Overall " + color(snapshot.overall()) + snapshot.overall() + " <gray>| Warn=<yellow>" + snapshot.warnings() + " <gray>| Error=<red>" + snapshot.errors());
            for (DoctorRow row : snapshot.rows()) {
                sendRow(context.sender(), row);
            }
            sendStrictSummary(context.sender(), request.strict(), snapshot.rows());
            send(context.sender(), "<dark_gray><st>--------------------------------------------------</st>");
            return;
        }
        List<DoctorRow> rows = api.doctor().check(category);
        for (DoctorRow row : rows) {
            sendRow(context.sender(), row);
        }
        sendStrictSummary(context.sender(), request.strict(), rows);
    }

    private DoctorRequest doctorRequest(CommandContext context) {
        String category = "all";
        boolean strict = false;
        for (String arg : context.args()) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            String normalized = arg.toLowerCase(Locale.ROOT);
            if (STRICT_MODES.contains(normalized)) {
                strict = true;
            } else if (category.equals("all")) {
                category = normalized;
            }
        }
        return new DoctorRequest(category, strict);
    }

    private List<String> doctorTabs(CommandContext context) {
        if (context.length() <= 1) {
            String token = context.arg(0, "").toLowerCase(Locale.ROOT);
            return DOCTOR_CATEGORIES.stream()
                    .filter(value -> value.startsWith(token))
                    .toList();
        }
        boolean strictAlready = java.util.Arrays.stream(context.args())
                .anyMatch(arg -> arg != null && STRICT_MODES.contains(arg.toLowerCase(Locale.ROOT)));
        if (strictAlready) {
            return List.of();
        }
        String token = context.arg(context.length() - 1, "").toLowerCase(Locale.ROOT);
        return STRICT_MODES.stream()
                .filter(value -> value.startsWith(token))
                .toList();
    }

    private void sendStrictSummary(CommandSender sender, boolean strict, List<DoctorRow> rows) {
        if (!strict) {
            return;
        }
        long warnings = rows.stream().filter(row -> row.severity() == vn.ledat.platform.api.doctor.Severity.WARN).count();
        long errors = rows.stream().filter(row -> row.severity() == vn.ledat.platform.api.doctor.Severity.ERROR).count();
        if (warnings == 0 && errors == 0) {
            send(sender, "<green>Strict doctor passed.");
            return;
        }
        send(sender, "<red>Strict doctor failed <gray>| warnings=<yellow>" + warnings + " <gray>| errors=<red>" + errors);
    }

    private void scheduler(CommandContext context) {
        LeDatScheduler scheduler = api.scheduler();
        Map<String, LeDatScheduler.SchedulerOwnerStats> owners = scheduler.ownerStats();
        int totalTracked = owners.values().stream().mapToInt(LeDatScheduler.SchedulerOwnerStats::trackedTasks).sum();
        int repeating = owners.values().stream().mapToInt(LeDatScheduler.SchedulerOwnerStats::repeatingTasks).sum();
        int oneShot = owners.values().stream().mapToInt(LeDatScheduler.SchedulerOwnerStats::oneShotTasks).sum();
        send(context.sender(), "<light_purple>Scheduler <gray>| backend=<white>" + scheduler.implementationName() + " <gray>| folia=<white>" + scheduler.isFolia() + " <gray>| primary=<white>" + scheduler.isPrimaryThread());
        send(context.sender(), "<gray>Tasks: tracked=<white>" + totalTracked + " <gray>repeating=<white>" + repeating + " <gray>oneShot=<white>" + oneShot + " <gray>owners=<white>" + owners.size());
        send(context.sender(), "<gray>Counters: submitted=<white>" + scheduler.submittedTasks() + " <gray>completed=<white>" + scheduler.completedTasks() + " <gray>failed=<white>" + scheduler.failedTasks() + " <gray>slow=<white>" + scheduler.slowTasks() + " <gray>cancelled=<white>" + scheduler.cancelledTasks());
        send(context.sender(), "<gray>Dùng <white>/ledatplatform scheduler owners</white> hoặc <white>/ledatplatform doctor scheduler</white> để xem sâu hơn.");
    }

    private void schedulerOwners(CommandContext context) {
        Map<String, LeDatScheduler.SchedulerOwnerStats> owners = api.scheduler().ownerStats();
        send(context.sender(), "<light_purple>Scheduler owners <gray>| plugins=<white>" + owners.size());
        if (owners.isEmpty()) {
            send(context.sender(), "<green>Không có tracked task owner đang active.");
            return;
        }
        owners.values().stream()
                .sorted(Comparator.comparingInt(LeDatScheduler.SchedulerOwnerStats::trackedTasks).reversed().thenComparing(LeDatScheduler.SchedulerOwnerStats::pluginName))
                .limit(40)
                .forEach(stats -> send(context.sender(), "<green>OK <dark_gray>| <white>" + MiniMessage.miniMessage().escapeTags(stats.pluginName())
                        + " <gray>tracked=<white>" + stats.trackedTasks()
                        + " <gray>repeating=<white>" + stats.repeatingTasks()
                        + " <gray>oneShot=<white>" + stats.oneShotTasks()
                        + " <gray>cancelledTracked=<white>" + stats.cancelledTasks()));
    }

    private void guard(CommandContext context) {
        GuardSnapshot snapshot = api.guards().snapshot();
        send(context.sender(), "<light_purple>Production guard <gray>| production=<white>" + snapshot.productionProfileEnabled() + " <gray>| primaryThread=<white>" + snapshot.primaryThread() + " <gray>| dbReject=<white>" + snapshot.mainThreadDbRejectEnabled());
        send(context.sender(), "<gray>Commands: executed=<white>" + api.commands().stats().executedHandlers() + " <gray>slow=<white>" + snapshot.slowCommandHandlers() + " <gray>| guardedOps=<white>" + snapshot.guardedOperations() + " <gray>failures=<white>" + snapshot.guardedFailures());
        send(context.sender(), "<gray>Executors: owners=<white>" + snapshot.managedExecutorOwners() + " <gray>registered=<white>" + snapshot.managedExecutorCount() + " <gray>active=<white>" + snapshot.activeManagedExecutorThreads() + " <gray>queued=<white>" + snapshot.queuedManagedExecutorTasks() + " <gray>rawThreads=<white>" + snapshot.unregisteredLeDatThreads());
        send(context.sender(), "<gray>Scheduler: owners=<white>" + snapshot.schedulerOwners() + " <gray>tracked=<white>" + snapshot.trackedSchedulerTasks() + " <gray>disabledOwners=<white>" + snapshot.disabledPluginTaskOwners() + " <gray>disabledTasks=<white>" + snapshot.disabledPluginTrackedTasks());
        api.executors().stats().values().stream()
                .sorted(Comparator.comparing(vn.ledat.platform.api.executor.ManagedExecutorService.ExecutorStats::pluginName)
                        .thenComparing(vn.ledat.platform.api.executor.ManagedExecutorService.ExecutorStats::name))
                .limit(12)
                .forEach(stats -> send(context.sender(), "<green>EXEC <dark_gray>| <white>" + MiniMessage.miniMessage().escapeTags(stats.pluginName()) + "/" + MiniMessage.miniMessage().escapeTags(stats.name())
                        + " <gray>pool=<white>" + stats.poolSize()
                        + " <gray>active=<white>" + stats.activeThreads()
                        + " <gray>queued=<white>" + stats.queuedTasks()
                        + " <gray>failed=<white>" + stats.failedTasks()
                        + " <gray>shutdown=<white>" + stats.shutdown()));
        api.executors().unregisteredLeDatThreadNames().stream()
                .limit(8)
                .forEach(name -> send(context.sender(), "<yellow>RAW <dark_gray>| <white>" + MiniMessage.miniMessage().escapeTags(name)));
    }

    private void commands(CommandContext context) {
        CommandStats stats = api.commands().stats();
        send(context.sender(), "<light_purple>Commands <gray>| roots=<white>" + stats.rootCommands() + " <gray>| nodes=<white>" + stats.commandNodes() + " <gray>| plugins=<white>" + stats.pluginCount());
        send(context.sender(), "<gray>Guards: playerOnly=<white>" + stats.playerOnlyNodes() + " <gray>cooldown=<white>" + stats.cooldownNodes() + " <gray>rateLimit=<white>" + stats.rateLimitedNodes());
        send(context.sender(), "<gray>Migration: legacy delegates=<yellow>" + stats.legacyDelegates() + " <gray>unregistered=<white>" + stats.missingPluginYml() + " <gray>executed=<white>" + stats.executedHandlers() + " <gray>slow=<white>" + stats.slowHandlers());
        send(context.sender(), "<gray>Commands: <white>/ledatplatform commands list [plugin]</white>, <white>legacy [plugin]</white>, <white>missing [plugin]</white>, <white>plugin <plugin></white>");
    }

    private void commandList(CommandContext context) {
        String filter = context.arg(0, "");
        List<CommandRoute> routes = commandRoutes(filter, false, false);
        send(context.sender(), "<light_purple>Command routes <gray>| showing=<white>" + routes.size() + " <gray>| total=<white>" + api.commands().routes().size() + (filter.isBlank() ? "" : " <gray>| filter=<white>" + MiniMessage.miniMessage().escapeTags(filter)));
        if (routes.isEmpty()) {
            send(context.sender(), "<yellow>Không có command route nào khớp filter.");
            return;
        }
        routes.forEach(route -> sendCommandRoute(context.sender(), route));
    }

    private void commandLegacy(CommandContext context) {
        String filter = context.arg(0, "");
        List<CommandRoute> routes = commandRoutes(filter, true, false);
        send(context.sender(), "<yellow>Legacy command delegates <gray>| showing=<white>" + routes.size() + " <gray>| total=<white>" + api.commands().stats().legacyDelegates());
        if (routes.isEmpty()) {
            send(context.sender(), "<green>Không có command route legacy delegate khớp filter.");
            return;
        }
        routes.forEach(route -> sendCommandRoute(context.sender(), route));
    }

    private void commandMissing(CommandContext context) {
        String filter = context.arg(0, "");
        List<CommandRoute> routes = commandRoutes(filter, false, true);
        send(context.sender(), "<red>Unregistered Paper commands <gray>| showing=<white>" + routes.size() + " <gray>| total=<white>" + api.commands().stats().missingPluginYml());
        if (routes.isEmpty()) {
            send(context.sender(), "<green>Không có command Paper nào lỗi registration khớp filter.");
            return;
        }
        routes.forEach(route -> sendCommandRoute(context.sender(), route));
    }

    private void commandPlugin(CommandContext context) {
        String plugin = context.arg(0, "");
        if (resolvePlugin(plugin) == null) {
            send(context.sender(), "<red>Không tìm thấy plugin: <white>" + MiniMessage.miniMessage().escapeTags(plugin));
            return;
        }
        List<CommandRoute> routes = commandRoutes(plugin, false, false);
        send(context.sender(), "<light_purple>Command routes for <white>" + MiniMessage.miniMessage().escapeTags(plugin) + " <gray>| showing=<white>" + routes.size());
        routes.forEach(route -> sendCommandRoute(context.sender(), route));
    }

    private List<CommandRoute> commandRoutes(String filter, boolean legacyOnly, boolean missingOnly) {
        String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        int limit = Math.max(1, contextListLimit());
        return api.commands().routes().values().stream()
                .filter(route -> !legacyOnly || route.legacyDelegate())
                .filter(route -> !missingOnly || !route.declaredInPluginYml())
                .filter(route -> needle.isBlank()
                        || route.pluginName().toLowerCase(Locale.ROOT).contains(needle)
                        || route.rootName().toLowerCase(Locale.ROOT).contains(needle)
                        || route.path().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(CommandRoute::pluginName, String.CASE_INSENSITIVE_ORDER).thenComparing(CommandRoute::path))
                .limit(limit)
                .toList();
    }

    private int contextListLimit() {
        org.bukkit.plugin.Plugin platformPlugin = org.bukkit.Bukkit.getPluginManager().getPlugin("LeDatPlatform");
        if (platformPlugin == null) {
            return 60;
        }
        return Math.max(1, platformPlugin.getConfig().getInt("command.diagnostics.list-limit", 60));
    }

    private void sendCommandRoute(CommandSender sender, CommandRoute route) {
        String state = !route.declaredInPluginYml() ? "<red>UNREGISTERED" : route.legacyDelegate() ? "<yellow>LEGACY" : "<green>PAPER";
        String guards = (route.playerOnly() ? " player" : "")
                + (route.cooldownSeconds() > 0 ? " cd=" + route.cooldownSeconds() + "s" : "")
                + (route.rateLimitMaxActions() > 0 ? " rl=" + route.rateLimitMaxActions() + "/" + Math.max(1L, route.rateLimitWindowMillis() / 1000L) + "s" : "");
        String permissions = route.requiredPermissions().isEmpty() ? "-" : String.join(",", route.requiredPermissions());
        send(sender, state + " <dark_gray>| <white>" + MiniMessage.miniMessage().escapeTags(route.pluginName()) + " <gray>/" + MiniMessage.miniMessage().escapeTags(route.path()) + " <dark_gray>| <gray>perm=<white>" + MiniMessage.miniMessage().escapeTags(permissions) + " <dark_gray>| <gray>usage=<white>" + MiniMessage.miniMessage().escapeTags(route.usage()) + (guards.isBlank() ? "" : " <dark_gray>|<gray>" + guards));
        if (route.legacyDelegate() && !route.legacyDetail().isBlank()) {
            send(sender, "<dark_gray>  detail: <gray>" + MiniMessage.miniMessage().escapeTags(route.legacyDetail()));
        }
    }

    private List<String> commandTabs(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return List.of("stats", "list", "legacy", "missing", "plugin").stream()
                .filter(value -> value.startsWith(p))
                .toList();
    }

    private void storage(CommandContext context) {
        var stats = api.database().stats();
        var health = api.database().healthSnapshot();
        send(context.sender(), "<light_purple>Storage <gray>| mode=<white>" + stats.mode() + " <gray>| enabled=<white>" + stats.enabled() + " <gray>| healthy=<white>" + stats.healthy());
        send(context.sender(), "<gray>Root: <white>" + MiniMessage.miniMessage().escapeTags(stats.storageRoot()) + " <dark_gray>| <gray>units yaml=<white>" + stats.yamlStores() + " <gray>sqlite=<white>" + stats.sqliteStores() + " <gray>unhealthy=<white>" + health.unhealthyUnits() + " <gray>dirtyYaml=<white>" + health.dirtyYamlStores());
        send(context.sender(), "<gray>Pool active=<white>" + stats.activeConnections() + " <gray>idle=<white>" + stats.idleConnections() + " <gray>waiting=<white>" + stats.waitingThreads());
        send(context.sender(), "<gray>Executor pool=<white>" + stats.poolSize() + " <gray>queued=<white>" + stats.queuedTasks() + " <gray>rejected=<white>" + stats.rejectedTasks() + " <gray>pressure=<white>" + (health.queuePressure() || health.waitingPressure()));
        send(context.sender(), "<gray>Tasks submitted=<white>" + stats.submittedTasks() + " <gray>completed=<white>" + stats.completedTasks() + " <gray>failed=<white>" + stats.failedTasks() + " <gray>slow=<white>" + stats.slowTasks());
        send(context.sender(), "<gray>Commands: <white>/ledatplatform storage list</white>, <white>path [plugin]</white>, <white>flush [plugin]</white>, <white>checkpoint [plugin]</white>, <white>verify [plugin|all] [--deep]</white>, <white>health</white>, <white>close <plugin|all></white>");
        if (!stats.lastError().isBlank()) {
            send(context.sender(), "<red>Last error: <white>" + MiniMessage.miniMessage().escapeTags(stats.lastError()));
        }
    }

    private void storageList(CommandContext context) {
        String filter = context.arg(0, "").toLowerCase(Locale.ROOT);
        var units = api.database().storageUnits().values().stream()
                .filter(unit -> filter.isBlank() || unit.pluginId().contains(filter) || unit.name().contains(filter))
                .limit(40)
                .toList();
        send(context.sender(), "<light_purple>Storage units <gray>| showing=<white>" + units.size() + " <gray>| total=<white>" + api.database().storageUnits().size());
        if (units.isEmpty()) {
            send(context.sender(), "<yellow>Chưa có YAML/SQLite unit nào được mở qua Data Service.");
            return;
        }
        for (var unit : units) {
            send(context.sender(), (unit.healthy() ? "<green>OK" : "<yellow>WARN") + " <dark_gray>| <white>" + unit.pluginId() + "/" + unit.name() + " <gray>" + unit.type() + " <gray>open=<white>" + unit.open() + " <gray>dirty=<white>" + unit.dirty() + " <gray>failed=<white>" + unit.failedTasks());
        }
    }

    private void storagePath(CommandContext context) {
        org.bukkit.plugin.Plugin target = resolvePlugin(context.arg(0, context.plugin().getName()));
        if (target == null) {
            send(context.sender(), "<red>Không tìm thấy plugin: <white>" + MiniMessage.miniMessage().escapeTags(context.arg(0, "")));
            return;
        }
        send(context.sender(), "<light_purple>Storage path <gray>| plugin=<white>" + target.getName());
        send(context.sender(), "<gray>Folder: <white>" + MiniMessage.miniMessage().escapeTags(api.database().storageFolder(target).getPath()));
        send(context.sender(), "<gray>YAML chuẩn: <white>plugins/" + target.getName() + "/storage/data.yml");
        send(context.sender(), "<gray>SQLite chuẩn: <white>plugins/" + target.getName() + "/storage/data.db");
    }

    private void storageFlush(CommandContext context) {
        org.bukkit.plugin.Plugin target = context.has(0) ? resolvePlugin(context.arg(0)) : null;
        if (context.has(0) && target == null) {
            send(context.sender(), "<red>Không tìm thấy plugin: <white>" + MiniMessage.miniMessage().escapeTags(context.arg(0)));
            return;
        }
        send(context.sender(), "<yellow>Đang flush Data Service" + (target == null ? " toàn bộ" : " cho " + target.getName()) + "...");
        java.util.concurrent.CompletableFuture<Void> future = target == null ? api.database().flushAll() : api.database().flush(target);
        future.whenComplete((ignored, error) -> api.scheduler().sender(context.plugin(), context.sender(), () -> {
            if (error != null) {
                send(context.sender(), "<red>Flush thất bại: <white>" + MiniMessage.miniMessage().escapeTags(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            } else {
                send(context.sender(), "<green>Đã flush Data Service.");
            }
        }));
    }

    private void storageCheckpoint(CommandContext context) {
        org.bukkit.plugin.Plugin target = context.has(0) ? resolvePlugin(context.arg(0)) : null;
        if (context.has(0) && target == null) {
            send(context.sender(), "<red>Không tìm thấy plugin: <white>" + MiniMessage.miniMessage().escapeTags(context.arg(0)));
            return;
        }
        send(context.sender(), "<yellow>Đang checkpoint SQLite WAL" + (target == null ? " toàn bộ" : " cho " + target.getName()) + "...");
        java.util.concurrent.CompletableFuture<Void> future = target == null ? api.database().checkpointAll() : api.database().checkpoint(target);
        future.whenComplete((ignored, error) -> api.scheduler().sender(context.plugin(), context.sender(), () -> {
            if (error != null) {
                send(context.sender(), "<red>Checkpoint thất bại: <white>" + MiniMessage.miniMessage().escapeTags(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            } else {
                send(context.sender(), "<green>Đã checkpoint SQLite WAL.");
            }
        }));
    }

    private void storageVerify(CommandContext context) {
        StorageVerifyRequest request = storageVerifyRequest(context);
        org.bukkit.plugin.Plugin target = request.all() ? null : resolvePlugin(request.pluginName());
        if (!request.all() && target == null) {
            send(context.sender(), "<red>Không tìm thấy plugin: <white>" + MiniMessage.miniMessage().escapeTags(request.pluginName()));
            return;
        }
        send(context.sender(), "<yellow>Đang verify Data Service" + (request.all() ? " toàn bộ" : " cho " + target.getName()) + (request.deep() ? " <red>(deep)</red>" : "") + "...");
        api.database().verifyStorage(target, request.deep()).whenComplete((result, error) -> api.scheduler().sender(context.plugin(), context.sender(), () -> {
            if (error != null) {
                send(context.sender(), "<red>Storage verify thất bại: <white>" + MiniMessage.miniMessage().escapeTags(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                return;
            }
            String color = result.errors() > 0 ? "<red>" : result.warnings() > 0 ? "<yellow>" : "<green>";
            send(context.sender(), color + "Storage verify " + result.summary());
            result.details().stream().limit(12).forEach(line -> send(context.sender(), "<gray>- <white>" + MiniMessage.miniMessage().escapeTags(line)));
            result.failures().stream().limit(12).forEach(line -> send(context.sender(), "<red>- <white>" + MiniMessage.miniMessage().escapeTags(line)));
        }));
    }

    private StorageVerifyRequest storageVerifyRequest(CommandContext context) {
        boolean deep = false;
        String pluginName = "all";
        for (String arg : context.args()) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            String normalized = arg.toLowerCase(Locale.ROOT);
            if (VERIFY_MODES.contains(normalized)) {
                deep = true;
            } else {
                pluginName = arg;
            }
        }
        return new StorageVerifyRequest(pluginName.equalsIgnoreCase("all"), pluginName, deep);
    }

    private List<String> storageVerifyTabs(CommandContext context) {
        if (context.length() <= 1) {
            String token = context.arg(0, "");
            List<String> names = pluginTabs(token);
            String lower = token.toLowerCase(Locale.ROOT);
            for (String mode : VERIFY_MODES) {
                if (mode.startsWith(lower) && !names.contains(mode)) {
                    names.add(mode);
                }
            }
            return names;
        }
        boolean deepAlready = java.util.Arrays.stream(context.args())
                .anyMatch(arg -> arg != null && VERIFY_MODES.contains(arg.toLowerCase(Locale.ROOT)));
        if (deepAlready) {
            return List.of();
        }
        String token = context.arg(context.length() - 1, "").toLowerCase(Locale.ROOT);
        return VERIFY_MODES.stream().filter(value -> value.startsWith(token)).toList();
    }

    private void storageHealth(CommandContext context) {
        send(context.sender(), "<yellow>Đang chạy storage health-check async...");
        api.scheduler().async(context.plugin(), "platform-storage-health-command", () -> {
            api.database().healthCheck();
            api.scheduler().sender(context.plugin(), context.sender(), () -> {
                storage(context);
                storageList(new CommandContext(context.plugin(), context.sender(), context.label(), new String[0]));
            });
        });
    }

    private void storageClose(CommandContext context) {
        String pluginId = context.arg(0, "");
        if (pluginId.equalsIgnoreCase("all")) {
            for (String id : api.database().storageUnits().values().stream().map(vn.ledat.platform.api.database.DatabaseProvider.StorageUnit::pluginId).distinct().toList()) {
                api.database().closePlugin(id);
            }
            send(context.sender(), "<green>Đã close tất cả local SQLite units đang mở. Chúng sẽ lazy-open khi plugin dùng lại.");
            return;
        }
        api.database().closePlugin(pluginId);
        send(context.sender(), "<green>Đã close local SQLite units của <white>" + MiniMessage.miniMessage().escapeTags(pluginId));
    }

    private List<String> storageTabs(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return List.of("status", "list", "path", "flush", "checkpoint", "verify", "health", "close").stream()
                .filter(value -> value.startsWith(p))
                .toList();
    }

    private List<String> guardTabs(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return List.of("status").stream()
                .filter(value -> value.startsWith(p))
                .toList();
    }

    private List<String> pluginTabs(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> names = new java.util.ArrayList<>(java.util.Arrays.stream(org.bukkit.Bukkit.getPluginManager().getPlugins())
                .map(org.bukkit.plugin.Plugin::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(p))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList()));
        if ("all".startsWith(p)) {
            names.add("all");
        }
        return names;
    }

    private org.bukkit.plugin.Plugin resolvePlugin(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        org.bukkit.plugin.Plugin direct = org.bukkit.Bukkit.getPluginManager().getPlugin(name);
        if (direct != null) {
            return direct;
        }
        String normalized = name.replace('_', '-');
        for (org.bukkit.plugin.Plugin candidate : org.bukkit.Bukkit.getPluginManager().getPlugins()) {
            if (candidate.getName().equalsIgnoreCase(name) || candidate.getName().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    private void hooks(CommandContext context) {
        api.hooks().statuses().forEach(status -> send(context.sender(), (status.available() ? "<green>OK" : "<yellow>MISS") + " <gray>| <white>" + status.hookName() + " <gray>- " + status.detail()));
    }

    private void caches(CommandContext context) {
        var stats = api.userCaches().stats();
        send(context.sender(), "<light_purple>User caches <gray>| caches=<white>" + stats.caches() + " <gray>entries=<white>" + stats.entries()
                + " <gray>dirty=<white>" + stats.dirtyEntries() + " <gray>loading=<white>" + stats.inFlightLoads()
                + " <gray>evictions=<white>" + stats.evictions() + " <gray>failures=<white>" + stats.failures());
        api.userCaches().caches().values().stream().map(vn.ledat.platform.api.cache.UserCacheService.UserCache::stats)
                .sorted(Comparator.comparing(vn.ledat.platform.api.cache.UserCacheService.UserCacheStats::key)).limit(30)
                .forEach(row -> send(context.sender(), "<green>CACHE <dark_gray>| <white>" + row.key() + " <gray>size=<white>" + row.size()
                        + " <gray>dirty=<white>" + row.dirtyEntries() + " <gray>hits=<white>" + row.hits() + " <gray>misses=<white>" + row.misses()));
    }

    private void items(CommandContext context) {
        var stats = api.items().stats();
        send(context.sender(), "<light_purple>Custom items <gray>| adapters=<white>" + stats.adapters() + " <gray>available=<white>" + stats.availableAdapters()
                + " <gray>creates=<white>" + stats.creates() + " <gray>identifications=<white>" + stats.identifications() + " <gray>failures=<white>" + stats.failures());
        api.items().adapters().forEach((id, adapter) -> send(context.sender(), (adapter.available() ? "<green>OK" : "<yellow>MISS") + " <dark_gray>| <white>" + id));
    }

    private void profiles(CommandContext context) {
        var stats = api.profiles().stats();
        send(context.sender(), "<light_purple>GameProfile cache <gray>| entries=<white>" + stats.entries() + " <gray>loading=<white>" + stats.inFlight()
                + " <gray>hits=<white>" + stats.hits() + " <gray>misses=<white>" + stats.misses() + " <gray>failures=<white>" + stats.failures());
    }

    private void blocks(CommandContext context) {
        var stats = api.playerBlocks().stats();
        send(context.sender(), "<light_purple>Player block tracker <gray>| lookups=<white>" + stats.lookups() + " <gray>marks=<white>" + stats.marks()
                + " <gray>removals=<white>" + stats.removals() + " <gray>failures=<white>" + stats.failures());
    }

    private void economy(CommandContext context) {
        send(context.sender(), "<light_purple>Economy registry <gray>| providers=<white>" + api.economy().providers().size());
        api.economy().providers().forEach((id, adapter) -> send(context.sender(), (adapter.available() ? "<green>OK" : "<yellow>MISS")
                + " <dark_gray>| <white>" + id + " <gray>- " + MiniMessage.miniMessage().escapeTags(adapter.displayName())));
    }

    private void placeholders(CommandContext context) {
        send(context.sender(), "<light_purple>Placeholder routes: <white>" + api.placeholders().routes().size());
        api.placeholders().routes().stream().limit(40).forEach(route -> send(context.sender(), "<gray>- <white>%ledat_" + route.routeKey() + "_<param>% <dark_gray>| <gray>" + route.pluginName()));
    }

    private void language(CommandContext context) {
        if (context.has(0)) {
            String action = context.arg(0, "").toLowerCase(Locale.ROOT);
            switch (action) {
                case "set" -> {
                    if (context.length() < 3) {
                        send(context.sender(), "<red>Usage: <white>/ledatplatform language set <player> <locale>");
                        return;
                    }
                    String locale = context.arg(2, "");
                    if (!api.languages().requiredLocales().contains(vn.ledat.platform.api.language.LanguageService.normalize(locale))) {
                        send(context.sender(), "<red>Invalid locale. Available: <white>" + String.join(", ", api.languages().requiredLocales()));
                        return;
                    }
                    @SuppressWarnings("deprecation")
                    org.bukkit.OfflinePlayer target = org.bukkit.Bukkit.getOfflinePlayer(context.arg(1, ""));
                    api.languages().setLocale(target.getUniqueId(), locale, "ledatplatform-command");
                    send(context.sender(), "<green>Language set for <white>" + MiniMessage.miniMessage().escapeTags(target.getName() == null ? context.arg(1, "") : target.getName())
                            + " <gray>-> <white>" + api.languages().resolveLocale(target.getUniqueId()));
                    return;
                }
                case "clear" -> {
                    if (context.length() < 2) {
                        send(context.sender(), "<red>Usage: <white>/ledatplatform language clear <player>");
                        return;
                    }
                    @SuppressWarnings("deprecation")
                    org.bukkit.OfflinePlayer target = org.bukkit.Bukkit.getOfflinePlayer(context.arg(1, ""));
                    api.languages().clearLocale(target.getUniqueId());
                    send(context.sender(), "<green>Language override cleared for <white>" + MiniMessage.miniMessage().escapeTags(target.getName() == null ? context.arg(1, "") : target.getName()));
                    return;
                }
                case "reload" -> {
                    String target = context.arg(1, "all");
                    if (target.equalsIgnoreCase("all")) {
                        api.languages().reload();
                        send(context.sender(), "<green>Reloaded all language bundles and preferences.");
                        return;
                    }
                    org.bukkit.plugin.Plugin plugin = resolvePlugin(target);
                    if (plugin == null) {
                        send(context.sender(), "<red>Không tìm thấy plugin: <white>" + MiniMessage.miniMessage().escapeTags(target));
                        return;
                    }
                    api.languages().reload(plugin);
                    send(context.sender(), "<green>Reloaded language bundle for <white>" + MiniMessage.miniMessage().escapeTags(plugin.getName()));
                    return;
                }
                default -> {
                    send(context.sender(), "<red>Unknown language action. Use <white>set</white>, <white>clear</white>, or <white>reload</white>.");
                    return;
                }
            }
        }
        var stats = api.languages().stats();
        send(context.sender(), "<light_purple>Language <gray>| default=<white>" + api.languages().defaultLocale() + " <gray>| fallback=<white>" + api.languages().fallbackLocale());
        send(context.sender(), "<gray>Platform folder: <white>" + api.languages().languageFolder(context.plugin()).getPath());
        send(context.sender(), "<gray>Platform locales: <white>" + String.join(", ", api.languages().supportedLocales(context.plugin())) + " <dark_gray>| <gray>required=<white>" + String.join(", ", api.languages().requiredLocales()));
        send(context.sender(), "<gray>Runtime cache: plugins=<white>" + stats.registeredPlugins() + " <gray>bundles=<white>" + stats.loadedBundles() + " <gray>localeOverrides=<white>" + stats.localeOverrides() + " <gray>saved=<white>" + stats.savedLocales() + " <gray>listeners=<white>" + stats.listeners() + " <gray>bossbars=<white>" + stats.activeBossBars());
        send(context.sender(), "<gray>Message warnings: missingKeys=<white>" + stats.missingMessageWarningKeys() + " <gray>invalidKeys=<white>" + stats.invalidMessageWarningKeys());
        api.languages().send(context.sender(), null, "command.reload", Map.of());
    }

    private List<String> languageTabs(CommandContext context) {
        if (context.length() <= 1) {
            String token = context.arg(0, "").toLowerCase(Locale.ROOT);
            return List.of("set", "clear", "reload").stream()
                    .filter(value -> value.startsWith(token))
                    .toList();
        }
        String action = context.arg(0, "").toLowerCase(Locale.ROOT);
        if (action.equals("set")) {
            if (context.length() == 2) {
                return onlinePlayerTabs(context.arg(1, ""));
            }
            if (context.length() == 3) {
                String token = context.arg(2, "").toLowerCase(Locale.ROOT);
                return api.languages().requiredLocales().stream()
                        .filter(locale -> locale.toLowerCase(Locale.ROOT).startsWith(token))
                        .toList();
            }
        }
        if (action.equals("clear") && context.length() == 2) {
            return onlinePlayerTabs(context.arg(1, ""));
        }
        if (action.equals("reload") && context.length() == 2) {
            return pluginTabs(context.arg(1, ""));
        }
        return List.of();
    }

    private List<String> onlinePlayerTabs(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return org.bukkit.Bukkit.getOnlinePlayers().stream()
                .map(org.bukkit.entity.Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(p))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(40)
                .toList();
    }

    private void gui(CommandContext context) {
        var stats = api.guis().stats();
        send(context.sender(), "<green>GUI base active: holder/session/click guard/drag guard/pagination helper.");
        send(context.sender(), "<gray>Sessions=<white>" + stats.sessions() + " <gray>reopenGuards=<white>" + stats.reopenGuards() + " <gray>debouncedPlayers=<white>" + stats.clickDebounceEntries());
        send(context.sender(), "<gray>Blocked rapid clicks=<white>" + stats.blockedRapidClicks() + " <gray>expiredCleanups=<white>" + stats.expiredSessionCleanups() + " <gray>failedOpens=<white>" + stats.failedOpens());
    }


    private void dialogs(CommandContext context) {
        var stats = api.dialogs().stats();
        send(context.sender(), "<light_purple>Dialogs <gray>| native=<white>" + api.dialogs().nativeDialogsAvailable() + " <gray>| fallback=<white>" + api.dialogs().fallbackEnabled() + " <gray>| sessions=<white>" + stats.sessions() + " <gray>| overrides=<white>" + stats.displayOverrides());
        send(context.sender(), "<gray>Native status: <white>" + MiniMessage.miniMessage().escapeTags(api.dialogs().nativeStatus()));
        send(context.sender(), "<gray>Client gate: <white>" + MiniMessage.miniMessage().escapeTags(api.dialogs().clientStatus(context.sender() instanceof org.bukkit.entity.Player player ? player : null)));
        send(context.sender(), "<gray>Native actions=<white>" + stats.nativeActions() + " <gray>nativeFallbacks=<white>" + stats.nativeFallbacks() + " <gray>failedOpens=<white>" + stats.failedOpens() + " <gray>expiredCleanups=<white>" + stats.expiredSessionCleanups());
        send(context.sender(), "<gray>Rich tags: <white>" + (api.dialogs().richObjectComponentsAvailable() ? "object-components" : "fallback-glyph") + " <dark_gray>| <gray>Supports <white>&lt;item:diamond&gt;</white> and <white>&lt;head:{player}&gt;</white>");
        send(context.sender(), "<gray>Platform templates: <white>" + api.dialogs().templateCount(context.plugin()));
    }

    private void forms(CommandContext context) {
        var stats = api.forms().stats();
        Map<String, String> diagnostics = api.forms().diagnostics(context.sender() instanceof org.bukkit.entity.Player player ? player : null);
        send(context.sender(), "<light_purple>Bedrock Forms <gray>| enabled=<white>" + diagnostics.getOrDefault("enabled", "false")
                + " <gray>| bridge=<white>" + diagnostics.getOrDefault("bridge", "false")
                + " <gray>| sessions=<white>" + stats.sessions());
        send(context.sender(), "<gray>Floodgate: plugin=<white>" + diagnostics.getOrDefault("floodgate-plugin", "false")
                + " <gray>class=<white>" + diagnostics.getOrDefault("floodgate-class", "false")
                + " <gray>| Cumulus=<white>" + diagnostics.getOrDefault("cumulus-class", "false")
                + " <gray>| Geyser=<white>" + diagnostics.getOrDefault("geyser-plugin", "false"));
        send(context.sender(), "<gray>Routing: dialog=<white>" + diagnostics.getOrDefault("dialog-routing", "false")
                + " <gray>preferBedrock=<white>" + diagnostics.getOrDefault("prefer-for-bedrock", "false"));
        send(context.sender(), "<gray>Counters: opened=<white>" + stats.opened()
                + " <gray>submitted=<white>" + stats.submitted()
                + " <gray>closed=<white>" + stats.closed()
                + " <gray>expired=<white>" + stats.expired()
                + " <gray>failedOpens=<white>" + stats.failedOpens()
                + " <gray>invalid=<white>" + stats.invalidResponses()
                + " <gray>rapidBlocked=<white>" + stats.blockedRapidSubmits()
                + " <gray>dialogFallbacks=<white>" + stats.fallbacksToDialog());
        send(context.sender(), "<gray>Status: <white>" + MiniMessage.miniMessage().escapeTags(diagnostics.getOrDefault("status", "unknown")));
    }

    private void formsPlayer(CommandContext context) {
        org.bukkit.entity.Player target = org.bukkit.Bukkit.getPlayerExact(context.arg(0, ""));
        if (target == null) {
            send(context.sender(), "<red>Player is not online: <white>" + MiniMessage.miniMessage().escapeTags(context.arg(0, "")));
            return;
        }
        Map<String, String> diagnostics = api.forms().diagnostics(target);
        send(context.sender(), "<light_purple>Bedrock Forms player <gray>| <white>" + MiniMessage.miniMessage().escapeTags(target.getName()));
        send(context.sender(), "<gray>Bedrock=<white>" + diagnostics.getOrDefault("bedrock", "false")
                + " <gray>| canOpen=<white>" + api.forms().canOpen(target)
                + " <gray>| session=<white>" + (api.forms().session(target.getUniqueId()) == null ? "none" : api.forms().session(target.getUniqueId()).menuId()));
        send(context.sender(), "<gray>Status: <white>" + MiniMessage.miniMessage().escapeTags(diagnostics.getOrDefault("status", "unknown")));
    }

    private void formsTest(CommandContext context) {
        org.bukkit.entity.Player target = org.bukkit.Bukkit.getPlayerExact(context.arg(0, ""));
        if (target == null) {
            send(context.sender(), "<red>Player is not online: <white>" + MiniMessage.miniMessage().escapeTags(context.arg(0, "")));
            return;
        }
        if (!api.forms().isBedrock(target)) {
            send(context.sender(), "<yellow>Player is not Bedrock/Floodgate; PE form was not opened.");
            return;
        }
        var result = api.forms().simple(context.plugin(), "admin-test")
                .title(Component.text("LeDatPlatform Forms"))
                .content(Component.text("Bridge test form. Server-side callbacks are guarded."))
                .button("ok", Component.text("OK"), click -> api.languages().send(click.player(), context.plugin(), "forms.closed", Map.of()))
                .button("close", Component.text("Close"), click -> { })
                .open(target);
        send(context.sender(), result.opened()
                ? "<green>Sent Bedrock SimpleForm test to <white>" + MiniMessage.miniMessage().escapeTags(target.getName())
                : "<red>Could not open Bedrock form: <white>" + result.name());
    }

    private List<String> formsTabs(CommandContext context) {
        if (context.length() <= 1) {
            String token = context.arg(0, "").toLowerCase(Locale.ROOT);
            return List.of("status", "player", "test").stream().filter(value -> value.startsWith(token)).toList();
        }
        String action = context.arg(0, "").toLowerCase(Locale.ROOT);
        if ((action.equals("player") || action.equals("test")) && context.length() == 2) {
            return onlinePlayerTabs(context.arg(1, ""));
        }
        return List.of();
    }

    private void links(CommandContext context) {
        send(context.sender(), "<light_purple>Server Links <gray>| enabled=<white>" + api.serverLinks().enabled() + " <gray>| available=<white>" + api.serverLinks().available() + " <gray>| entries=<white>" + api.serverLinks().configuredCount() + " <gray>| sent=<white>" + api.serverLinks().sentCount());
        send(context.sender(), "<gray>Status: <white>" + MiniMessage.miniMessage().escapeTags(api.serverLinks().status()));
        if (context.sender() instanceof org.bukkit.entity.Player player) {
            api.serverLinks().apply(player);
            send(context.sender(), "<green>Đã gửi lại Server Links cho bạn.");
        }
    }

    private void audit(CommandContext context) {
        api.audit().record(new vn.ledat.platform.api.audit.AuditRecord("platform", "debug", "command_audit_test", null, null, "ok", "text", "manual", Map.of("sender", context.sender().getName()), null));
        send(context.sender(), "<green>Đã ghi audit test async/YAML tùy storage mode.");
    }

    private void debug(CommandContext context) {
        status(context);
        doctor(new CommandContext(context.plugin(), context.sender(), context.label(), new String[]{"all"}));
    }

    private void sendRow(CommandSender sender, DoctorRow row) {
        send(sender, color(row.severity()) + row.severity() + " <dark_gray>| <white>" + row.id() + " <dark_gray>| <gray>" + row.state() + " <dark_gray>- <white>" + MiniMessage.miniMessage().escapeTags(row.detail()));
    }

    private String color(vn.ledat.platform.api.doctor.Severity severity) {
        return switch (severity) {
            case OK -> "<green>";
            case WARN -> "<yellow>";
            case ERROR -> "<red>";
        };
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(MINI.deserialize(message));
    }

    private record DoctorRequest(String category, boolean strict) {
    }

    private record StorageVerifyRequest(boolean all, String pluginName, boolean deep) {
    }
}
