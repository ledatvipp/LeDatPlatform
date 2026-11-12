package vn.ledat.platform.api;

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

public interface LeDatPlatformApi {
    String apiVersion();
    String platformVersion();
    boolean isFolia();

    LeDatScheduler scheduler();
    PlatformLogger logger();
    LanguageService languages();
    ConfigService configs();
    DatabaseProvider database();
    CooldownService cooldowns();
    RateLimitService rateLimits();
    CommandService commands();
    PlaceholderRouter placeholders();
    AuditLedger audit();
    default LedgerService ledger() { return LedgerService.disabled(); }
    GuiService guis();
    DialogService dialogs();
    default BedrockFormService forms() { return BedrockFormService.disabled(); }
    default SoundService sounds() { return SoundService.disabled(); }
    ServerLinkService serverLinks();
    HookRegistry hooks();
    DoctorService doctor();
    PlatformGuardService guards();
    ManagedExecutorService executors();
    default UserCacheService userCaches() { return UserCacheService.disabled(); }
    default CustomItemService items() { return CustomItemService.disabled(); }
    default GameProfileService profiles() { return GameProfileService.disabled(); }
    default PlayerBlockTracker playerBlocks() { return PlayerBlockTracker.disabled(); }
    default EconomyService economy() { return EconomyService.disabled(); }
}
