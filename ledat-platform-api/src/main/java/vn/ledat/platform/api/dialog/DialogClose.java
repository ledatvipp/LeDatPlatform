package vn.ledat.platform.api.dialog;

import org.bukkit.entity.Player;

public record DialogClose(Player player, DialogSession session, boolean nativeDialog, String reason, Object sourceEvent) {
}
