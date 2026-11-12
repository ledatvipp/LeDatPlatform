package vn.ledat.platform.core.logging;

import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.logging.PlatformLogger;

import java.util.Map;
import java.util.StringJoiner;
import java.util.logging.Level;

public final class DefaultPlatformLogger implements PlatformLogger {
    private volatile boolean debugEnabled;

    public DefaultPlatformLogger(boolean debugEnabled) {
        this.debugEnabled = debugEnabled;
    }

    public void setDebugEnabled(boolean debugEnabled) {
        this.debugEnabled = debugEnabled;
    }

    @Override
    public void info(Plugin plugin, String message) {
        log(plugin, Level.INFO, message, null);
    }

    @Override
    public void warn(Plugin plugin, String message) {
        log(plugin, Level.WARNING, message, null);
    }

    @Override
    public void error(Plugin plugin, String message, Throwable error) {
        log(plugin, Level.SEVERE, message, error);
    }

    @Override
    public void debug(Plugin plugin, String message) {
        if (debugEnabled) {
            log(plugin, Level.INFO, "[debug] " + message, null);
        }
    }

    @Override
    public void audit(Plugin plugin, String action, Map<String, String> fields) {
        StringJoiner joiner = new StringJoiner(", ");
        if (fields != null) {
            fields.forEach((key, value) -> joiner.add(key + "=" + mask(key, value)));
        }
        log(plugin, Level.INFO, "[audit] " + action + " {" + joiner + "}", null);
    }

    @Override
    public void log(Plugin plugin, Level level, String message, Throwable error) {
        Plugin target = plugin == null ? null : plugin;
        String safe = sanitize(message);
        if (target == null) {
            java.util.logging.Logger.getLogger("LeDatPlatform").log(level, safe, error);
            return;
        }
        if (error == null) {
            target.getLogger().log(level, safe);
        } else {
            target.getLogger().log(level, safe, error);
        }
    }

    @Override
    public boolean debugEnabled() {
        return debugEnabled;
    }

    private String sanitize(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("(?i)(password|token|secret|apikey|api-key)=([^,\\s]+)", "$1=***")
                .replaceAll("(?i)(password|token|secret|apikey|api-key):([^,\\s]+)", "$1:***");
    }

    private String mask(String key, String value) {
        if (key != null && key.matches("(?i).*(password|token|secret|apikey|api-key).*")) {
            return "***";
        }
        return value == null ? "" : value;
    }
}
