package vn.ledat.platform.api.serverlink;

import net.kyori.adventure.text.Component;

public record ServerLinkEntry(
        String id,
        Component label,
        String url,
        String permission,
        boolean enabled
) {
}
