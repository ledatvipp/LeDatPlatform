package vn.ledat.platform.core.economy;

import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.economy.EconomyService;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultEconomyService implements EconomyService {
    private final Map<String, Registration> providers = new ConcurrentHashMap<>();

    @Override
    public void register(Plugin owner, EconomyAdapter adapter) {
        if (owner == null || adapter == null || normalize(adapter.id()).isBlank()) return;
        providers.put(normalize(adapter.id()), new Registration(owner, adapter));
    }

    @Override
    public void unregister(Plugin owner) {
        if (owner != null) providers.entrySet().removeIf(entry -> entry.getValue().owner.equals(owner));
    }

    @Override
    public Optional<EconomyAdapter> provider(String id) {
        Registration registration = providers.get(normalize(id));
        return registration == null || !available(registration.adapter) ? Optional.empty() : Optional.of(registration.adapter);
    }

    @Override
    public Optional<EconomyAdapter> primary() {
        Optional<EconomyAdapter> vault = provider("vault");
        if (vault.isPresent()) return vault;
        return providers.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue).map(Registration::adapter).filter(this::available).findFirst();
    }

    @Override
    public Map<String, EconomyAdapter> providers() {
        Map<String, EconomyAdapter> result = new LinkedHashMap<>();
        providers.forEach((id, registration) -> result.put(id, registration.adapter));
        return Map.copyOf(result);
    }

    private boolean available(EconomyAdapter adapter) {
        try { return adapter.available(); } catch (Throwable ignored) { return false; }
    }

    private static final java.util.regex.Pattern UNSAFE_ID_PATTERN = java.util.regex.Pattern.compile("[^a-z0-9_.-]");

    private String normalize(String value) {
        return value == null ? "" : UNSAFE_ID_PATTERN.matcher(value.trim().toLowerCase(Locale.ROOT)).replaceAll("_");
    }

    private record Registration(Plugin owner, EconomyAdapter adapter) { }
}
