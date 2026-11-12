package vn.ledat.platform.core.dialog;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;

final class ViaVersionClientVersionDetector {
    private final Plugin plugin;
    private String minClientVersion;
    private boolean assumeCompatibleWithoutDetector;
    private int minProtocol = -1;
    private String status = "not-initialized";

    ViaVersionClientVersionDetector(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        this.minClientVersion = plugin.getConfig().getString("dialogs.native.min-client-version", "1.21.7");
        this.assumeCompatibleWithoutDetector = plugin.getConfig().getBoolean("dialogs.native.assume-compatible-without-client-detector", true);
        this.minProtocol = resolveProtocol(minClientVersion);
        if (Bukkit.getPluginManager().getPlugin("ViaVersion") == null) {
            this.status = assumeCompatibleWithoutDetector ? "ViaVersion missing; assuming compatible" : "ViaVersion missing; native disabled for unknown clients";
        } else if (minProtocol < 0) {
            this.status = "ViaVersion present; could not resolve min protocol for " + minClientVersion;
        } else {
            this.status = "ViaVersion present; min=" + minClientVersion + " protocol=" + minProtocol;
        }
    }

    boolean compatible(Player player) {
        ClientInfo info = info(player);
        return info.compatible();
    }

    String status(Player player) {
        ClientInfo info = info(player);
        return info.status();
    }

    String detectorStatus() {
        return status;
    }

    private ClientInfo info(Player player) {
        if (player == null) {
            return new ClientInfo(false, "player-null");
        }
        if (Bukkit.getPluginManager().getPlugin("ViaVersion") == null) {
            return new ClientInfo(assumeCompatibleWithoutDetector, status);
        }
        int protocol = playerProtocol(player.getUniqueId());
        if (protocol < 0 || minProtocol < 0) {
            return new ClientInfo(assumeCompatibleWithoutDetector, "unknown-protocol; " + status);
        }
        boolean ok = protocol >= minProtocol;
        return new ClientInfo(ok, "protocol=" + protocol + ", min=" + minProtocol + ", compatible=" + ok);
    }

    private int playerProtocol(UUID uuid) {
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            Method method = api.getClass().getMethod("getPlayerVersion", UUID.class);
            Object value = method.invoke(api, uuid);
            return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
        } catch (Throwable error) {
            return -1;
        }
    }

    private int resolveProtocol(String version) {
        if (version == null || version.isBlank()) {
            return -1;
        }
        String trimmed = version.trim().toLowerCase(Locale.ROOT);
        if (trimmed.matches("\\d+")) {
            return Integer.parseInt(trimmed);
        }
        try {
            Class<?> protocolVersion = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
            Method getClosest = protocolVersion.getMethod("getClosest", String.class);
            Object resolved = getClosest.invoke(null, trimmed);
            Method getVersion = resolved.getClass().getMethod("getVersion");
            Object value = getVersion.invoke(resolved);
            return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private record ClientInfo(boolean compatible, String status) {
    }
}
