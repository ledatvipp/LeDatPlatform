package vn.ledat.platform.api.logging;

import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.logging.Level;

public interface PlatformLogger {
    void info(Plugin plugin, String message);
    void warn(Plugin plugin, String message);
    void error(Plugin plugin, String message, Throwable error);
    void debug(Plugin plugin, String message);
    void audit(Plugin plugin, String action, Map<String, String> fields);
    void log(Plugin plugin, Level level, String message, Throwable error);
    boolean debugEnabled();
}
