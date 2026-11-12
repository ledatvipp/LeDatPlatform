package vn.ledat.platform.api.serverlink;

import org.bukkit.entity.Player;

import java.util.List;

public interface ServerLinkService {
    void reload();
    void apply(Player player);
    void applyAllOnline();
    boolean available();
    boolean enabled();
    String status();
    int configuredCount();
    int sentCount();
    List<ServerLinkEntry> entries();
}
