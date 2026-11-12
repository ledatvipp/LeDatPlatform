package vn.ledat.platform.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Optional;

public final class LeDatPlatformProvider {
    private static volatile LeDatPlatformApi instance;

    private LeDatPlatformProvider() {
    }

    public static Optional<LeDatPlatformApi> optional() {
        LeDatPlatformApi cached = instance;
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            RegisteredServiceProvider<LeDatPlatformApi> registration = Bukkit.getServicesManager().getRegistration(LeDatPlatformApi.class);
            if (registration == null) {
                return Optional.empty();
            }
            instance = registration.getProvider();
            return Optional.of(instance);
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    public static LeDatPlatformApi get() {
        return optional().orElseThrow(() -> new IllegalStateException("LeDatPlatform API is not registered. Add depend: [LeDatPlatform] in plugin.yml."));
    }

    public static void setInstance(LeDatPlatformApi api) {
        instance = api;
    }

    public static void clear(LeDatPlatformApi api) {
        if (instance == api) {
            instance = null;
        }
    }
}
