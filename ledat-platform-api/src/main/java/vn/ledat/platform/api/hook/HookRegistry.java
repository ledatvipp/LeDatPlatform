package vn.ledat.platform.api.hook;

import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;

public interface HookRegistry {
    void register(Plugin owner, String hookName, Object adapter, boolean available, String detail);
    void unregister(Plugin owner, String hookName);

    /** Remove all hook entries owned by a plugin. */
    default void unregisterPlugin(Plugin owner) { }

    boolean available(String hookName);
    <T> Optional<T> adapter(String hookName, Class<T> type);
    List<HookStatus> statuses();
    void reload();
}
