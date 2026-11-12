package vn.ledat.platform.common.suite;

import java.util.Set;

/**
 * Standard capability ids used by migrated LeDat plugins.
 *
 * Keep these values lowercase-kebab-case because the interop registry is intentionally
 * string based. This makes it safe across plugin classloaders even when
 * ledat-platform-common is shaded and relocated inside each plugin jar.
 */
public final class LeDatCapabilities {
    public static final String ADMIN = "admin";
    public static final String AFK = "afk";
    public static final String ANTI_SPAM = "anti-spam";
    public static final String BOUNTY = "bounty";
    public static final String CHAT = "chat";
    public static final String COSMETIC = "cosmetic";
    public static final String CRATE = "crate";
    public static final String ECONOMY = "economy";
    public static final String GUI = "gui";
    public static final String LANGUAGE = "language";
    public static final String LEADERBOARD = "leaderboard";
    public static final String LOGGING = "logging";
    public static final String NIGHT_VISION = "night-vision";
    public static final String ORDER = "order";
    public static final String PLAYER_DATA = "player-data";
    public static final String PLATFORM = "platform";
    public static final String SHARED_DATABASE = "shared-database";
    public static final String MIGRATION_REGISTRY = "migration-registry";
    public static final String NODE_REGISTRY = "node-registry";
    public static final String AUDIT_LOG = "audit-log";
    public static final String PHANTOM_PROTECTION = "phantom-protection";
    public static final String PLAYER_SETTINGS = "player-settings";
    public static final String RTP = "rtp";
    public static final String SHOP = "shop";
    public static final String STORAGE = "storage";
    public static final String TEAM = "team";
    public static final String TELEPORT = "teleport";
    public static final String TOOL = "tool";
    public static final String UTILITY = "utility";
    public static final String WORTH = "worth";

    public static final Set<String> STANDARD = Set.of(
            ADMIN, AFK, ANTI_SPAM, BOUNTY, CHAT, COSMETIC, CRATE, ECONOMY,
            GUI, LANGUAGE, LEADERBOARD, LOGGING, NIGHT_VISION, ORDER, PLAYER_DATA,
            PLATFORM, SHARED_DATABASE, MIGRATION_REGISTRY, NODE_REGISTRY, AUDIT_LOG,
            PHANTOM_PROTECTION, PLAYER_SETTINGS, RTP, SHOP, STORAGE, TEAM, TELEPORT, TOOL, UTILITY, WORTH
    );

    private LeDatCapabilities() {
    }
}
