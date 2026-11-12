package vn.ledat.platform.api.bedrockform;

import org.bukkit.entity.Player;

public record BedrockFormClose(
        Player player,
        BedrockFormSession session,
        String reason,
        Object sourceEvent
) {
}
