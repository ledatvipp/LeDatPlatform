package vn.ledat.platform.api.command;

import java.time.Instant;

/**
 * Aggregated runtime command diagnostics for /ledatplatform commands and doctor checks.
 */
public record CommandStats(
        int rootCommands,
        int commandNodes,
        int legacyDelegates,
        int missingPluginYml,
        int playerOnlyNodes,
        int cooldownNodes,
        int rateLimitedNodes,
        int pluginCount,
        long executedHandlers,
        long slowHandlers,
        Instant createdAt
) {
    public CommandStats(
            int rootCommands,
            int commandNodes,
            int legacyDelegates,
            int missingPluginYml,
            int playerOnlyNodes,
            int cooldownNodes,
            int rateLimitedNodes,
            int pluginCount,
            Instant createdAt
    ) {
        this(rootCommands, commandNodes, legacyDelegates, missingPluginYml, playerOnlyNodes, cooldownNodes,
                rateLimitedNodes, pluginCount, 0L, 0L, createdAt);
    }
}
