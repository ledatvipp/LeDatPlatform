package vn.ledat.platform.common.scheduler;

import org.bukkit.plugin.Plugin;

/** Factory for plugin-local scheduler facade instances. */
public final class SchedulerProvider {
    private SchedulerProvider() {
    }

    public static SchedulerFacade create(Plugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin cannot be null");
        }
        return new FoliaLibSchedulerFacade(plugin);
    }
}
