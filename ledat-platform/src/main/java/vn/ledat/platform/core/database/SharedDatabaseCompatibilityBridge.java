package vn.ledat.platform.core.database;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.audit.AuditLedger;
import vn.ledat.platform.api.audit.AuditRecord;
import vn.ledat.platform.api.database.StorageMode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Classloader-safe compatibility provider for legacy/shaded modules that discover the shared
 * LeDatPlatform database through JVM system properties. Only JDK types cross this boundary.
 *
 * <p>The bridge intentionally exposes shared SQL only when LeDatPlatform is in MYSQL mode.
 * Some legacy consumers use MySQL-specific SQL (for example UNIX_TIMESTAMP/INSERT IGNORE), so
 * publishing a SQLite connection here would be unsafe. Per-plugin SQLite remains available via
 * the typed {@link vn.ledat.platform.api.database.DatabaseProvider#sqliteStore(Plugin, String)} API.</p>
 */
public final class SharedDatabaseCompatibilityBridge {
    public static final String PROVIDER_KEY = "ledat.platform.database.provider.v1";
    public static final String STATUS_KEY = "ledat.platform.database.status.v1";

    private final Plugin plugin;
    private final DefaultDatabaseProvider database;
    private final AuditLedger audit;

    public SharedDatabaseCompatibilityBridge(Plugin plugin, DefaultDatabaseProvider database, AuditLedger audit) {
        this.plugin = plugin;
        this.database = database;
        this.audit = audit;
    }

    public synchronized void publish() {
        if (!plugin.getConfig().getBoolean("database.compatibility-provider.enabled", true)) {
            unpublish();
            return;
        }
        synchronized (System.getProperties()) {
            System.getProperties().put(PROVIDER_KEY, this);
            System.getProperties().put(STATUS_KEY, status());
        }
    }

    public synchronized void unpublish() {
        synchronized (System.getProperties()) {
            Object current = System.getProperties().get(PROVIDER_KEY);
            if (current == this) {
                System.getProperties().remove(PROVIDER_KEY);
            }
            System.getProperties().put(STATUS_KEY, status());
        }
    }

    public boolean isEnabled() {
        return database != null && (database.mode() == StorageMode.MYSQL || database.mode() == StorageMode.SHARED_MYSQL) && database.enabled();
    }

    public boolean isHealthy() {
        return isEnabled() && database.healthy();
    }

    public boolean isReadOnly() {
        return false;
    }

    public String nodeId() {
        String configured = firstNonBlank(
                plugin.getConfig().getString("database.node-id"),
                plugin.getConfig().getString("server.node-id"),
                plugin.getConfig().getString("network.node-id")
        );
        if (configured != null) {
            return safeId(configured, "node");
        }
        return safeId(Bukkit.getServer().getName(), "node");
    }

    public String clusterId() {
        return safeId(firstNonBlank(
                plugin.getConfig().getString("database.cluster-id"),
                plugin.getConfig().getString("server.cluster-id"),
                plugin.getConfig().getString("network.cluster-id")
        ), "default");
    }

    public String databaseName() {
        return firstNonBlank(
                plugin.getConfig().getString("storage.mysql.database"),
                plugin.getConfig().getString("database.database"),
                "unknown"
        );
    }

    public String storageMode() {
        return database == null ? "unknown" : database.mode().name().toLowerCase(java.util.Locale.ROOT);
    }

    public Connection getConnection(String requester) throws SQLException {
        if (!isEnabled()) {
            throw new SQLException("LeDatPlatform shared MYSQL provider is disabled; current mode=" + (database == null ? "unknown" : database.mode()));
        }
        if (!isHealthy()) {
            throw new SQLException("LeDatPlatform shared MYSQL provider is unhealthy: " + (database == null ? "unknown" : database.lastError()));
        }
        return database.borrowConnection(requester == null || requester.isBlank() ? "shared-compat" : requester);
    }

    public <T> CompletableFuture<T> executeAsync(String requester, Callable<T> callable) {
        if (database == null) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new SQLException("LeDatPlatform database provider is not initialized"));
            return failed;
        }
        return database.submitExternal(requester == null || requester.isBlank() ? "shared-compat" : requester, callable);
    }

    public String tableName(String pluginId, String localName) {
        return database == null ? safeSqlIdentifier(localName, "table") : database.tableName(pluginId, localName);
    }

    public CompletableFuture<Void> auditAsync(String pluginName, String eventType, String detail) {
        if (audit == null) {
            return CompletableFuture.completedFuture(null);
        }
        return audit.record(new AuditRecord(
                safeId(pluginName, "unknown"),
                "shared_database",
                safeId(eventType, "event"),
                null,
                null,
                "recorded",
                "detail",
                detail == null ? "" : detail,
                Map.of("source", "compat-provider"),
                Instant.now()
        ));
    }

    public Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("provider", getClass().getName());
        map.put("enabled", isEnabled());
        map.put("healthy", isHealthy());
        map.put("storageMode", storageMode());
        map.put("nodeId", nodeId());
        map.put("clusterId", clusterId());
        map.put("database", databaseName());
        map.put("lastError", database == null ? "not-initialized" : database.lastError());
        if (database != null) {
            var stats = database.stats();
            map.put("activeConnections", stats.activeConnections());
            map.put("idleConnections", stats.idleConnections());
            map.put("waitingThreads", stats.waitingThreads());
            map.put("queuedTasks", stats.queuedTasks());
            map.put("submittedTasks", stats.submittedTasks());
            map.put("completedTasks", stats.completedTasks());
            map.put("failedTasks", stats.failedTasks());
        }
        return map;
    }

    public void registerPlugin(String pluginName, String storageMode, Map<String, ?> metadata) {
        updatePluginHealth(pluginName, storageMode, "UNKNOWN", "", -1L, metadata);
    }

    public void updatePluginHealth(String pluginName, String storageMode, String healthStatus, String lastError, long latencyMs, Map<String, ?> metadata) {
        // Compatibility telemetry is intentionally lightweight in the API-based provider.
        if (plugin.getConfig().getBoolean("debug.enabled", false)) {
            plugin.getLogger().fine("Shared DB plugin health: plugin=" + safeId(pluginName, "unknown")
                    + ", storage=" + safeId(storageMode, "unknown")
                    + ", health=" + safeId(healthStatus, "UNKNOWN")
                    + ", latencyMs=" + latencyMs
                    + ", error=" + (lastError == null ? "" : lastError));
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String safeId(String raw, String fallback) {
        String value = raw == null ? fallback : raw.trim();
        value = value.replaceAll("[^A-Za-z0-9_.:-]", "_").replaceAll("_+", "_");
        return value.isBlank() ? fallback : value;
    }

    private String safeSqlIdentifier(String raw, String fallback) {
        String value = raw == null ? fallback : raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        value = value.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return value.isBlank() ? fallback : value;
    }
}
