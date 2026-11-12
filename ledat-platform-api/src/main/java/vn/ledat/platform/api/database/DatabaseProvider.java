package vn.ledat.platform.api.database;

import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface DatabaseProvider extends AutoCloseable {
    StorageMode mode();
    boolean enabled();
    boolean healthy();
    String status();
    String lastError();
    String tableName(String pluginId, String localName);

    Connection borrowConnection(String requester) throws SQLException;

    <T> CompletableFuture<T> query(String requester, SqlFunction<T> function);
    CompletableFuture<Void> execute(String requester, SqlConsumer consumer);
    CompletableFuture<Void> transaction(String requester, SqlConsumer consumer);

    /**
     * Legacy platform-owned YAML store. Kept for existing integrations.
     * New child plugins should prefer {@link #yamlStore(Plugin, String)} so files live under
     * plugins/&lt;PluginName&gt;/storage/&lt;store&gt;.yml.
     */
    Optional<YamlStore> yamlStore(String pluginId);

    /**
     * Standard LeDat per-plugin storage folder: plugins/&lt;PluginName&gt;/storage/.
     */
    File storageFolder(Plugin owner);

    /**
     * Standard LeDat YAML store: plugins/&lt;PluginName&gt;/storage/&lt;storeName&gt;.yml.
     */
    Optional<YamlStore> yamlStore(Plugin owner, String storeName);

    /**
     * Standard LeDat local SQLite store: plugins/&lt;PluginName&gt;/storage/&lt;databaseName&gt;.db.
     */
    Optional<SqliteStore> sqliteStore(Plugin owner, String databaseName);

    /**
     * Snapshot of all stores currently opened through this provider.
     */
    Map<String, StorageUnit> storageUnits();

    /**
     * Flush dirty YAML stores owned by a plugin. Pass a platform plugin instance to flush platform-owned stores.
     */
    CompletableFuture<Void> flush(Plugin owner);

    /**
     * Flush all dirty YAML stores. SQL stores are auto-commit/transaction backed.
     */
    CompletableFuture<Void> flushAll();

    /**
     * Close managed local SQLite stores owned by a plugin. They will reopen lazily on next access.
     */
    void closePlugin(String pluginId);

    /**
     * Close managed plugin storage without holding the caller thread for a YAML flush or SQLite checkpoint.
     * Implementations should complete the returned future only after ownership has been released.
     */
    default CompletableFuture<Void> closePluginAsync(String pluginId) {
        return CompletableFuture.runAsync(() -> closePlugin(pluginId));
    }

    /**
     * Reserve plugin storage for close after a caller-owned persistence barrier has settled.
     * A failed barrier must not leave an opened store behind; implementations still attempt cleanup.
     */
    default CompletableFuture<Void> closePluginAfter(String pluginId, CompletionStage<?> persistenceBarrier) {
        if (persistenceBarrier == null) {
            return closePluginAsync(pluginId);
        }
        return persistenceBarrier.handle((ignored, error) -> null)
                .thenCompose(ignored -> closePluginAsync(pluginId))
                .toCompletableFuture();
    }

    /**
     * Ask managed local SQLite stores owned by a plugin to checkpoint WAL data to the main database file.
     * Implementations that do not manage SQLite stores may no-op.
     */
    default CompletableFuture<Void> checkpoint(Plugin owner) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Ask all managed local SQLite stores to checkpoint WAL data to the main database file.
     * Useful before backup/restart. Implementations that do not manage SQLite stores may no-op.
     */
    default CompletableFuture<Void> checkpointAll() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Verify the platform SQL provider and/or managed per-plugin storage units asynchronously.
     * The default no-op keeps older providers binary/source compatible.
     *
     * @param owner optional plugin owner filter; {@code null} verifies all opened units plus platform SQL.
     * @param deep when true, implementations may run slower integrity checks.
     */
    default CompletableFuture<StorageVerificationResult> verifyStorage(Plugin owner, boolean deep) {
        return CompletableFuture.completedFuture(new StorageVerificationResult(
                owner == null ? "all" : owner.getName(), deep, 0, 0, 0, 0, java.util.List.of(), java.util.List.of(), "not-supported"));
    }

    DatabaseStats stats();

    /**
     * Derived storage health snapshot for admin/doctor diagnostics.
     */
    default StorageHealth healthSnapshot() {
        DatabaseStats current = stats();
        Map<String, StorageUnit> units = storageUnits();
        int unhealthyUnits = (int) units.values().stream()
                .filter(unit -> !unit.healthy() || unit.failedTasks() > 0)
                .count();
        int dirtyYamlStores = (int) units.values().stream()
                .filter(unit -> "yaml".equalsIgnoreCase(unit.type()) && unit.dirty())
                .count();
        int openUnits = (int) units.values().stream().filter(StorageUnit::open).count();
        boolean queuePressure = current.queuedTasks() > 0 && current.queuedTasks() >= Math.max(128, current.poolSize() * 256);
        boolean waitingPressure = current.waitingThreads() > 0;
        return new StorageHealth(
                current.enabled(),
                current.healthy(),
                mode(),
                status(),
                units.size(),
                openUnits,
                unhealthyUnits,
                dirtyYamlStores,
                current.queuedTasks(),
                current.failedTasks(),
                current.rejectedTasks(),
                current.slowTasks(),
                queuePressure,
                waitingPressure,
                lastError()
        );
    }

    void reload();
    void healthCheck();

    @Override
    void close();

    @FunctionalInterface
    interface SqlFunction<T> {
        T apply(Connection connection) throws Exception;
    }

    @FunctionalInterface
    interface SqlConsumer {
        void accept(Connection connection) throws Exception;
    }

    interface YamlStore {
        String pluginId();
        String name();
        File file();
        boolean dirty();
        CompletableFuture<Void> set(String key, Object value);
        CompletableFuture<Void> remove(String key);
        CompletableFuture<Optional<Object>> get(String key);
        CompletableFuture<Map<String, Object>> snapshot();
        CompletableFuture<YamlConfiguration> configurationSnapshot();
        CompletableFuture<Void> replace(YamlConfiguration configuration);
        CompletableFuture<Void> save();
    }

    interface SqliteStore extends AutoCloseable {
        String pluginId();
        String name();
        File file();
        boolean healthy();
        String status();
        String lastError();
        Connection borrowConnection(String requester) throws SQLException;
        <T> CompletableFuture<T> query(String requester, SqlFunction<T> function);
        CompletableFuture<Void> execute(String requester, SqlConsumer consumer);
        CompletableFuture<Void> transaction(String requester, SqlConsumer consumer);
        void healthCheck();
        @Override void close();
    }

    record StorageUnit(
            String key,
            String pluginId,
            String name,
            String type,
            String path,
            boolean open,
            boolean healthy,
            boolean dirty,
            long submittedTasks,
            long completedTasks,
            long failedTasks,
            String status,
            String lastError
    ) {
    }

    record StorageVerificationResult(
            String scope,
            boolean deep,
            int checkedUnits,
            int ok,
            int warnings,
            int errors,
            java.util.List<String> details,
            java.util.List<String> failures,
            String summary
    ) {
        public boolean healthy() {
            return errors == 0;
        }
    }

    record StorageHealth(
            boolean enabled,
            boolean healthy,
            StorageMode mode,
            String status,
            int units,
            int openUnits,
            int unhealthyUnits,
            int dirtyYamlStores,
            int queuedTasks,
            long failedTasks,
            long rejectedTasks,
            long slowTasks,
            boolean queuePressure,
            boolean waitingPressure,
            String lastError
    ) {
        public boolean ok() {
            return enabled && healthy && unhealthyUnits == 0 && rejectedTasks == 0 && !queuePressure && !waitingPressure;
        }
    }

    record DatabaseStats(
            StorageMode mode,
            boolean enabled,
            boolean healthy,
            int activeConnections,
            int idleConnections,
            int waitingThreads,
            int queuedTasks,
            int poolSize,
            long submittedTasks,
            long completedTasks,
            long failedTasks,
            long rejectedTasks,
            long slowTasks,
            int yamlStores,
            int sqliteStores,
            String storageRoot,
            String lastError
    ) {
        /** Compatibility constructor for older internal call-sites. */
        public DatabaseStats(
                StorageMode mode,
                boolean enabled,
                boolean healthy,
                int activeConnections,
                int idleConnections,
                int waitingThreads,
                int queuedTasks,
                int poolSize,
                long submittedTasks,
                long completedTasks,
                long failedTasks,
                long rejectedTasks,
                long slowTasks,
                String lastError
        ) {
            this(mode, enabled, healthy, activeConnections, idleConnections, waitingThreads, queuedTasks, poolSize,
                    submittedTasks, completedTasks, failedTasks, rejectedTasks, slowTasks, 0, 0, "", lastError);
        }
    }
}
