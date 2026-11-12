package vn.ledat.platform.api.bedrockform;

public record BedrockFormStats(
        int sessions,
        long opened,
        long submitted,
        long closed,
        long expired,
        long failedOpens,
        long invalidResponses,
        long blockedRapidSubmits,
        long bedrockPlayersDetected,
        long fallbacksToDialog
) {
}
