package vn.ledat.platform.common.i18n;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-local player language registry shared across relocated LeDat plugins.
 * Values are JDK types only because each plugin shades platform-common.
 */
public final class LeDatLanguageRegistry {
    private static final String REGISTRY_KEY = "ledat.suite.player-language.v2";

    private LeDatLanguageRegistry() {
    }

    /**
     * Publishes a suite-level language preference. Registry values are always
     * canonical and supported so one bad legacy plugin cannot poison every other
     * plugin's message renderer with an unknown language code.
     */
    public static void set(UUID playerId, String language) {
        if (playerId == null) {
            return;
        }
        registry().put(playerId.toString(), LeDatLanguageCodes.normalizeSupported(language, LeDatLanguageCodes.DEFAULT));
    }

    public static void setIfSupported(UUID playerId, String language, String fallback) {
        if (playerId == null) {
            return;
        }
        registry().put(playerId.toString(), LeDatLanguageCodes.normalizeSupported(language, fallback));
    }

    /**
     * Seeds the shared registry without overriding a language that was already
     * provided by the suite-level Settings GUI. Use this for legacy per-plugin
     * playerdata during plugin startup so old local files cannot undo the
     * player's current /settings language choice.
     */
    public static void setIfAbsent(UUID playerId, String language) {
        if (playerId == null) {
            return;
        }
        registry().putIfAbsent(playerId.toString(), LeDatLanguageCodes.normalizeSupported(language, LeDatLanguageCodes.DEFAULT));
    }

    public static boolean contains(UUID playerId) {
        return playerId != null && registry().containsKey(playerId.toString());
    }

    public static Optional<String> get(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        String value = registry().get(playerId.toString());
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(LeDatLanguageCodes.normalizeSupported(value, LeDatLanguageCodes.DEFAULT));
    }

    public static String getOrDefault(UUID playerId, String defaultLanguage) {
        return get(playerId).orElse(LeDatLanguageCodes.normalize(defaultLanguage));
    }

    public static void remove(UUID playerId) {
        if (playerId != null) {
            registry().remove(playerId.toString());
        }
    }

    public static void clear() {
        registry().clear();
    }

    public static Map<String, String> snapshot() {
        return Map.copyOf(registry());
    }

    public static int size() {
        return registry().size();
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, String> registry() {
        synchronized (System.getProperties()) {
            Object existing = System.getProperties().get(REGISTRY_KEY);
            if (existing instanceof ConcurrentHashMap<?, ?> map) {
                return (ConcurrentHashMap<String, String>) map;
            }
            ConcurrentHashMap<String, String> created = new ConcurrentHashMap<>();
            System.getProperties().put(REGISTRY_KEY, created);
            return created;
        }
    }
}
