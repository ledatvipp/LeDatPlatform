package vn.ledat.platform.core.api;

import vn.ledat.platform.api.LeDatPlatformApi;
import vn.ledat.platform.api.audit.AuditLedger;
import vn.ledat.platform.api.bedrockform.BedrockFormService;
import vn.ledat.platform.api.block.PlayerBlockTracker;
import vn.ledat.platform.api.cache.UserCacheService;
import vn.ledat.platform.api.command.CommandService;
import vn.ledat.platform.api.config.ConfigService;
import vn.ledat.platform.api.cooldown.CooldownService;
import vn.ledat.platform.api.cooldown.RateLimitService;
import vn.ledat.platform.api.database.DatabaseProvider;
import vn.ledat.platform.api.doctor.DoctorService;
import vn.ledat.platform.api.dialog.DialogService;
import vn.ledat.platform.api.executor.ManagedExecutorService;
import vn.ledat.platform.api.economy.EconomyService;
import vn.ledat.platform.api.gui.GuiService;
import vn.ledat.platform.api.guard.PlatformGuardService;
import vn.ledat.platform.api.hook.HookRegistry;
import vn.ledat.platform.api.item.CustomItemService;
import vn.ledat.platform.api.language.LanguageService;
import vn.ledat.platform.api.ledger.LedgerService;
import vn.ledat.platform.api.logging.PlatformLogger;
import vn.ledat.platform.api.placeholder.PlaceholderRouter;
import vn.ledat.platform.api.profile.GameProfileService;
import vn.ledat.platform.api.scheduler.LeDatScheduler;
import vn.ledat.platform.api.serverlink.ServerLinkService;
import vn.ledat.platform.api.sound.SoundService;

public final class DefaultLeDatPlatformApi implements LeDatPlatformApi {
    public static final String API_VERSION = "2.10.0";

    private final String platformVersion;
    private final LeDatScheduler scheduler;
    private final PlatformLogger logger;
    private final LanguageService languages;
    private final ConfigService configs;
    private final DatabaseProvider database;
    private final CooldownService cooldowns;
    private final RateLimitService rateLimits;
    private final CommandService commands;
    private final PlaceholderRouter placeholders;
    private final AuditLedger audit;
    private final LedgerService ledger;
    private final GuiService guis;
    private final DialogService dialogs;
    private final BedrockFormService forms;
    private final SoundService sounds;
    private final ServerLinkService serverLinks;
    private final HookRegistry hooks;
    private final DoctorService doctor;
    private final PlatformGuardService guards;
    private final ManagedExecutorService executors;
    private final UserCacheService userCaches;
    private final CustomItemService items;
    private final GameProfileService profiles;
    private final PlayerBlockTracker playerBlocks;
    private final EconomyService economy;

    public DefaultLeDatPlatformApi(
            String platformVersion,
            LeDatScheduler scheduler,
            PlatformLogger logger,
            LanguageService languages,
            ConfigService configs,
            DatabaseProvider database,
            CooldownService cooldowns,
            RateLimitService rateLimits,
            CommandService commands,
            PlaceholderRouter placeholders,
            AuditLedger audit,
            LedgerService ledger,
            GuiService guis,
            DialogService dialogs,
            BedrockFormService forms,
            SoundService sounds,
            ServerLinkService serverLinks,
            HookRegistry hooks,
            DoctorService doctor,
            PlatformGuardService guards,
            ManagedExecutorService executors,
            UserCacheService userCaches,
            CustomItemService items,
            GameProfileService profiles,
            PlayerBlockTracker playerBlocks,
            EconomyService economy
    ) {
        this.platformVersion = platformVersion;
        this.scheduler = scheduler;
        this.logger = logger;
        this.languages = languages;
        this.configs = configs;
        this.database = database;
        this.cooldowns = cooldowns;
        this.rateLimits = rateLimits;
        this.commands = commands;
        this.placeholders = placeholders;
        this.audit = audit;
        this.ledger = ledger;
        this.guis = guis;
        this.dialogs = dialogs;
        this.forms = forms == null ? BedrockFormService.disabled() : forms;
        this.sounds = sounds;
        this.serverLinks = serverLinks;
        this.hooks = hooks;
        this.doctor = doctor;
        this.guards = guards;
        this.executors = executors;
        this.userCaches = userCaches == null ? UserCacheService.disabled() : userCaches;
        this.items = items == null ? CustomItemService.disabled() : items;
        this.profiles = profiles == null ? GameProfileService.disabled() : profiles;
        this.playerBlocks = playerBlocks == null ? PlayerBlockTracker.disabled() : playerBlocks;
        this.economy = economy == null ? EconomyService.disabled() : economy;
    }

    @Override public String apiVersion() { return API_VERSION; }
    @Override public String platformVersion() { return platformVersion; }
    @Override public boolean isFolia() { return scheduler.isFolia(); }
    @Override public LeDatScheduler scheduler() { return scheduler; }
    @Override public PlatformLogger logger() { return logger; }
    @Override public LanguageService languages() { return languages; }
    @Override public ConfigService configs() { return configs; }
    @Override public DatabaseProvider database() { return database; }
    @Override public CooldownService cooldowns() { return cooldowns; }
    @Override public RateLimitService rateLimits() { return rateLimits; }
    @Override public CommandService commands() { return commands; }
    @Override public PlaceholderRouter placeholders() { return placeholders; }
    @Override public AuditLedger audit() { return audit; }
    @Override public LedgerService ledger() { return ledger; }
    @Override public GuiService guis() { return guis; }
    @Override public DialogService dialogs() { return dialogs; }
    @Override public BedrockFormService forms() { return forms; }
    @Override public SoundService sounds() { return sounds; }
    @Override public ServerLinkService serverLinks() { return serverLinks; }
    @Override public HookRegistry hooks() { return hooks; }
    @Override public DoctorService doctor() { return doctor; }
    @Override public PlatformGuardService guards() { return guards; }
    @Override public ManagedExecutorService executors() { return executors; }
    @Override public UserCacheService userCaches() { return userCaches; }
    @Override public CustomItemService items() { return items; }
    @Override public GameProfileService profiles() { return profiles; }
    @Override public PlayerBlockTracker playerBlocks() { return playerBlocks; }
    @Override public EconomyService economy() { return economy; }
}
