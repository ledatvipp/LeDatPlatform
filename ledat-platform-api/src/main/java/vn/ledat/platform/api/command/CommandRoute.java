package vn.ledat.platform.api.command;

import java.util.List;

/**
 * Immutable runtime metadata for one command node registered through LeDatPlatform CommandService.
 * Root commands and subcommands are both represented as routes so diagnostics can show exactly
 * which plugin still exposes legacy command delegates.
 */
public record CommandRoute(
        String pluginName,
        String rootName,
        String path,
        String permission,
        List<String> requiredPermissions,
        String usage,
        String usageKey,
        String description,
        boolean playerOnly,
        boolean executorConfigured,
        boolean legacyDelegate,
        String legacyDetail,
        boolean declaredInPluginYml,
        int minArgs,
        int maxArgs,
        long cooldownSeconds,
        int rateLimitMaxActions,
        long rateLimitWindowMillis,
        List<String> children
) {
}
