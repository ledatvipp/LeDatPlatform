package vn.ledat.platform.common.hook;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

/**
 * @deprecated use {@link vn.ledat.platform.api.hook.HookGuard}.
 */
@Deprecated(forRemoval = false)
public final class LeDatHookGuard {
    private LeDatHookGuard() {
    }

    public static boolean pluginEnabled(JavaPlugin owner, String hookName, String featureName, boolean required) {
        return vn.ledat.platform.api.hook.HookGuard.pluginEnabled(owner, hookName, featureName, required);
    }

    public static Optional<Plugin> plugin(JavaPlugin owner, String hookName, String featureName, boolean required) {
        return vn.ledat.platform.api.hook.HookGuard.plugin(owner, hookName, featureName, required);
    }

    public static <T> Optional<T> service(JavaPlugin owner, Class<T> serviceClass, String hookName, String featureName, boolean required) {
        return vn.ledat.platform.api.hook.HookGuard.service(owner, serviceClass, hookName, featureName, required);
    }

    public static void feature(JavaPlugin owner, String hookName, String featureName, boolean required, boolean available, String detail) {
        vn.ledat.platform.api.hook.HookGuard.feature(owner, hookName, featureName, required, available, detail);
    }
}
