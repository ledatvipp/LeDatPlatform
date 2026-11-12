package vn.ledat.platform.common.database;

import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classloader-safe bridge to the optional LeDatPlatform shared database service.
 *
 * <p>Every LeDat plugin may shade and relocate ledat-platform-common. For that reason this
 * class intentionally does not depend on a shared Java interface. The runtime provider is
 * published in JVM system properties by the LeDatPlatform plugin and invoked reflectively.
 * Only JDK types cross the boundary: String, Map, Callable, CompletableFuture and java.sql.Connection.</p>
 */
public final class LeDatPlatformDatabase {
    public static final String PROVIDER_KEY = "ledat.platform.database.provider.v1";
    public static final String STATUS_KEY = "ledat.platform.database.status.v1";

    private static final ConcurrentHashMap<String, Boolean> WARNED = new ConcurrentHashMap<>();

    private LeDatPlatformDatabase() {
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws Exception;
    }

    public static boolean isProviderPresent() {
        return provider().isPresent();
    }

    public static boolean isEnabled() {
        return callBoolean("isEnabled", false);
    }

    public static boolean isHealthy() {
        return callBoolean("isHealthy", false);
    }

    public static String nodeId() {
        return callString("nodeId", "unknown");
    }

    public static String clusterId() {
        return callString("clusterId", "default");
    }

    public static String databaseName() {
        return callString("databaseName", "unknown");
    }

    public static String storageMode() {
        Object value = status().get("storageMode");
        return value == null ? "local" : String.valueOf(value);
    }

    public static boolean isReadOnly() {
        Object value = status().get("readOnly");
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    public static Connection getConnection(String requester) throws SQLException {
        Object p = provider().orElseThrow(() -> new SQLException("LeDatPlatform database provider is not registered"));
        try {
            Method method = p.getClass().getMethod("getConnection", String.class);
            Object value = method.invoke(p, sanitizeRequester(requester));
            if (value instanceof Connection connection) {
                return connection;
            }
            throw new SQLException("LeDatPlatform provider returned non-Connection value");
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new SQLException("LeDatPlatform provider is incompatible: " + e.getMessage(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sql) {
                throw sql;
            }
            throw new SQLException("LeDatPlatform provider failed: " + (cause == null ? e.getMessage() : cause.getMessage()), cause);
        }
    }

    public static <T> CompletableFuture<T> supplyAsync(String requester, SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        Callable<T> callable = () -> {
            try (Connection connection = getConnection(requester)) {
                return work.run(connection);
            }
        };
        return executeCallableAsync(requester, callable);
    }

    public static CompletableFuture<Void> runAsync(String requester, SqlWork<Void> work) {
        return supplyAsync(requester, work);
    }

    public static <T> CompletableFuture<T> transactionAsync(String requester, SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        Callable<T> callable = () -> {
            try (Connection connection = getConnection(requester)) {
                boolean oldAutoCommit = connection.getAutoCommit();
                try {
                    connection.setAutoCommit(false);
                    T result = work.run(connection);
                    connection.commit();
                    return result;
                } catch (Exception error) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackError) {
                        error.addSuppressed(rollbackError);
                    }
                    throw error;
                } finally {
                    try {
                        connection.setAutoCommit(oldAutoCommit);
                    } catch (SQLException ignored) {
                        // connection is closing
                    }
                }
            }
        };
        return executeCallableAsync(requester, callable);
    }

    @SuppressWarnings("unchecked")
    private static <T> CompletableFuture<T> executeCallableAsync(String requester, Callable<T> callable) {
        Object p = provider().orElse(null);
        if (p != null) {
            try {
                Method method = p.getClass().getMethod("executeAsync", String.class, Callable.class);
                Object value = method.invoke(p, sanitizeRequester(requester), callable);
                if (value instanceof CompletableFuture<?> future) {
                    return (CompletableFuture<T>) future;
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall back below for older platform providers.
            }
        }
        CompletableFuture<T> failed = new CompletableFuture<>();
        failed.completeExceptionally(new SQLException("LeDatPlatform async executor is not available"));
        return failed;
    }

    public static boolean addBufferedCounter(String pluginId, String counterNamespace, String counterKey, long delta) {
        Object p = provider().orElse(null);
        if (p == null || delta == 0L) {
            return false;
        }
        try {
            Method method = p.getClass().getMethod("addBufferedCounter", String.class, String.class, String.class, long.class);
            Object value = method.invoke(p, sanitizePluginId(pluginId), safeIdentifier(counterNamespace, "counter"), counterKey == null ? "unknown" : counterKey, delta);
            return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    public static boolean coalesceAsync(String requester, String bucket, String key, SqlWork<Void> work) {
        Objects.requireNonNull(work, "work");
        Object p = provider().orElse(null);
        if (p == null) {
            return false;
        }
        String safeRequester = sanitizeRequester(requester);
        String safeBucket = safeIdentifier(bucket, "default");
        String safeKey = key == null ? "unknown" : key;
        try {
            Method method = p.getClass().getMethod("enqueueCoalescedSqlWork", String.class, String.class, String.class, Object.class);
            Object value = method.invoke(p, safeRequester, safeBucket, safeKey, work);
            return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        } catch (ReflectiveOperationException ignored) {
            // Older platform provider: fall back to one connection per coalesced callable.
        }
        Callable<Void> callable = () -> {
            try (Connection connection = getConnection(requester)) {
                work.run(connection);
                return null;
            }
        };
        try {
            Method method = p.getClass().getMethod("enqueueCoalesced", String.class, String.class, String.class, Callable.class);
            Object value = method.invoke(p, safeRequester, safeBucket, safeKey, callable);
            return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    public static void flushWriteBehind() {
        Object p = provider().orElse(null);
        if (p == null) {
            return;
        }
        try {
            Method method = p.getClass().getMethod("flushWriteBehind");
            method.invoke(p);
        } catch (ReflectiveOperationException ignored) {
            // optional
        }
    }

    public static String tableName(String pluginId, String localName) {
        Object p = provider().orElse(null);
        if (p == null) {
            return safeIdentifier(localName, "unknown_table");
        }
        try {
            Method method = p.getClass().getMethod("tableName", String.class, String.class);
            Object value = method.invoke(p, sanitizePluginId(pluginId), safeIdentifier(localName, "table"));
            String table = value == null ? "" : String.valueOf(value).trim();
            return table.isEmpty() ? safeIdentifier(localName, "unknown_table") : table;
        } catch (ReflectiveOperationException e) {
            return safeIdentifier(localName, "unknown_table");
        }
    }

    public static boolean acquireMigrationLock(String pluginId, String lockName, long ttlMs) {
        Object p = provider().orElse(null);
        if (p == null) {
            return false;
        }
        try {
            Method method = p.getClass().getMethod("acquireMigrationLock", String.class, String.class, long.class);
            Object value = method.invoke(p, sanitizePluginId(pluginId), safeIdentifier(lockName, "lock"), ttlMs);
            return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    public static void releaseMigrationLock(String pluginId, String lockName) {
        Object p = provider().orElse(null);
        if (p == null) {
            return;
        }
        try {
            Method method = p.getClass().getMethod("releaseMigrationLock", String.class, String.class);
            method.invoke(p, sanitizePluginId(pluginId), safeIdentifier(lockName, "lock"));
        } catch (ReflectiveOperationException ignored) {
            // best effort
        }
    }

    public static boolean hasMigration(String pluginId, int version, String checksum) {
        Object p = provider().orElse(null);
        if (p == null) {
            return false;
        }
        try {
            Method method = p.getClass().getMethod("hasMigration", String.class, int.class, String.class);
            Object value = method.invoke(p, sanitizePluginId(pluginId), version, checksum == null ? "" : checksum);
            return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    public static void recordMigration(String pluginId, int version, String checksum) throws SQLException {
        Object p = provider().orElseThrow(() -> new SQLException("LeDatPlatform database provider is not registered"));
        try {
            Method method = p.getClass().getMethod("recordMigration", String.class, int.class, String.class);
            method.invoke(p, sanitizePluginId(pluginId), version, checksum == null ? "" : checksum);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new SQLException("LeDatPlatform provider migration API is incompatible: " + e.getMessage(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sql) {
                throw sql;
            }
            throw new SQLException("LeDatPlatform migration record failed: " + (cause == null ? e.getMessage() : cause.getMessage()), cause);
        }
    }

    public static CompletableFuture<Void> auditAsync(String pluginName, String eventType, String detail) {
        Object p = provider().orElse(null);
        if (p == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            Method method = p.getClass().getMethod("auditAsync", String.class, String.class, String.class);
            Object value = method.invoke(p, sanitizePluginId(pluginName), safeIdentifier(eventType, "event"), detail == null ? "" : detail);
            if (value instanceof CompletableFuture<?> future) {
                return future.thenApply(ignored -> null);
            }
        } catch (ReflectiveOperationException ignored) {
            // optional
        }
        return CompletableFuture.completedFuture(null);
    }

    public static Map<String, Object> status() {
        Object p = provider().orElse(null);
        if (p == null) {
            Object fallback = System.getProperties().get(STATUS_KEY);
            if (fallback instanceof Map<?, ?> map) {
                return sanitizeMap(map);
            }
            return Map.of("provider", "missing", "enabled", false, "healthy", false);
        }
        try {
            Method method = p.getClass().getMethod("status");
            Object value = method.invoke(p);
            if (value instanceof Map<?, ?> map) {
                return sanitizeMap(map);
            }
        } catch (ReflectiveOperationException ignored) {
            // fall through
        }
        return Map.of("provider", p.getClass().getName(), "enabled", isEnabled(), "healthy", isHealthy());
    }

    public static void registerPlugin(JavaPlugin plugin, String storageMode, Map<String, ?> metadata) {
        if (plugin == null) {
            return;
        }
        registerPlugin(plugin.getName(), storageMode, metadata);
    }

    public static void registerPlugin(String pluginName, String storageMode, Map<String, ?> metadata) {
        updatePluginHealth(pluginName, storageMode, "UNKNOWN", "", -1L, metadata);
    }

    public static void updatePluginHealth(String pluginName, String storageMode, String healthStatus, String lastError, long latencyMs, Map<String, ?> metadata) {
        if (pluginName == null || pluginName.isBlank()) {
            return;
        }
        Object p = provider().orElse(null);
        if (p == null) {
            return;
        }
        try {
            Method method = p.getClass().getMethod("updatePluginHealth", String.class, String.class, String.class, String.class, long.class, Map.class);
            method.invoke(p, pluginName, storageMode == null ? "unknown" : storageMode, healthStatus == null ? "UNKNOWN" : healthStatus, lastError == null ? "" : lastError, latencyMs, metadata == null ? Map.of() : new LinkedHashMap<>(metadata));
        } catch (ReflectiveOperationException ignored) {
            try {
                Method method = p.getClass().getMethod("registerPlugin", String.class, String.class, Map.class);
                method.invoke(p, pluginName, storageMode == null ? "unknown" : storageMode, metadata == null ? Map.of() : new LinkedHashMap<>(metadata));
            } catch (ReflectiveOperationException ignored2) {
                // Optional telemetry only.
            }
        }
    }

    public static void warnOnce(JavaPlugin plugin, String key, String message) {
        if (plugin == null || message == null || message.isBlank()) {
            return;
        }
        String warnKey = plugin.getName() + ":" + key;
        if (WARNED.putIfAbsent(warnKey, true) == null) {
            plugin.getLogger().warning(message);
        }
    }

    private static Optional<Object> provider() {
        synchronized (System.getProperties()) {
            return Optional.ofNullable(System.getProperties().get(PROVIDER_KEY));
        }
    }

    private static boolean callBoolean(String methodName, boolean fallback) {
        Object p = provider().orElse(null);
        if (p == null) {
            return fallback;
        }
        try {
            Object value = p.getClass().getMethod(methodName).invoke(p);
            return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        } catch (ReflectiveOperationException ignored) {
            return fallback;
        }
    }

    private static String callString(String methodName, String fallback) {
        Object p = provider().orElse(null);
        if (p == null) {
            return fallback;
        }
        try {
            Object value = p.getClass().getMethod(methodName).invoke(p);
            String text = value == null ? "" : String.valueOf(value).trim();
            return text.isEmpty() ? fallback : text;
        } catch (ReflectiveOperationException ignored) {
            return fallback;
        }
    }

    private static String sanitizeRequester(String requester) {
        return sanitizePluginId(requester == null ? "unknown" : requester);
    }

    private static String sanitizePluginId(String value) {
        return safeIdentifier(value == null ? "unknown" : value.toLowerCase().replace('-', '_'), "unknown");
    }

    private static String safeIdentifier(String value, String fallback) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) {
            return fallback;
        }
        String clean = raw.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_");
        if (clean.isBlank()) {
            return fallback;
        }
        if (Character.isDigit(clean.charAt(0))) {
            clean = "t_" + clean;
        }
        return clean;
    }

    private static Map<String, Object> sanitizeMap(Map<?, ?> input) {
        if (input == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> output = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                output.put(String.valueOf(entry.getKey()), sanitizeMap(nested));
            } else {
                output.put(String.valueOf(entry.getKey()), value);
            }
        }
        return Collections.unmodifiableMap(output);
    }
}
