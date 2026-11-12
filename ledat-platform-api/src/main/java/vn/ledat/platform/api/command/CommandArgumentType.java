package vn.ledat.platform.api.command;

/**
 * Platform-safe command argument types exposed by LeDatPlatform CommandService.
 *
 * <p>The API deliberately avoids leaking Brigadier implementation classes to
 * child plugins. The core maps these small metadata types to Paper Brigadier
 * arguments and suggestions at registration time.</p>
 */
public enum CommandArgumentType {
    WORD,
    STRING,
    GREEDY_STRING,
    INTEGER,
    LONG,
    DOUBLE,
    BOOLEAN,
    UUID,
    DURATION,
    MONEY,
    PLAYER_NAME,
    OFFLINE_PLAYER_NAME,
    PLUGIN_NAME,
    SERVER_NAME,
    PERMISSION,
    WORLD_NAME,
    MATERIAL_NAME,
    CUSTOM_ITEM_KEY,
    LANGUAGE,
    ENUM
}
