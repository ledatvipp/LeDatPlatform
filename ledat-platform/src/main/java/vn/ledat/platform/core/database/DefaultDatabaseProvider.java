package vn.ledat.platform.core.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.database.DatabaseProvider;
import vn.ledat.platform.api.database.StorageMode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class DefaultDatabaseProvider implements DatabaseProvider {
    private static final int LOCAL_SQLITE_STORE_WARNING_THRESHOLD = 4;
    private static final Executor DIRECT_EXECUTOR = Runnable::run;
    private static final Pattern IDENTIFIER_UNSAFE_PATTERN = Pattern.compile("[^a-z0-9_]");
    private static final Pattern FILE_SEGMENT_UNSAFE_PATTERN = Pattern.compile("[^a-z0-9._-]");
    private static final Pattern REPEATED_UNDERSCORE_PATTERN = Pattern.compile("_+");
    private static final Pattern IDENTIFIER_EDGE_UNDERSCORE_PATTERN = Pattern.compile("^_+|_+$");
    private static final Pattern FILE_SEGMENT_EDGE_PATTERN = Pattern.compile("^[_\\-.]+|[_\\-.]+$");
    private static final Pattern SECRET_PATTERN = Pattern.compile("(?i)(password|token|secret|apikey|api-key)=([^,\\s]+)");

    private final Plugin plugin;
    private final Logger logger;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong slow = new AtomicLong();
    private final AtomicLong queueWarnings = new AtomicLong();
    private final AtomicLong queueWaitWarningAt = new AtomicLong();
    private final AtomicLong queueWaitSamples = new AtomicLong();
    private final AtomicLong totalQueueWaitNanos = new AtomicLong();
    private final AtomicLong maxQueueWaitNanos = new AtomicLong();
    private final AtomicLong mainThreadBorrowWarningAt = new AtomicLong();
    private final AtomicLong slowWarningAt = new AtomicLong();
    private final AtomicLong sqliteCheckpoints = new AtomicLong();
    private final AtomicLong sqliteCheckpointFailures = new AtomicLong();
    private final AtomicLong sqliteQuickCheckFailures = new AtomicLong();
    private final AtomicLong sqliteIntegrityCheckFailures = new AtomicLong();
    private final AtomicLong storageVerifyRuns = new AtomicLong();
    private final AtomicLong storageVerifyFailures = new AtomicLong();
    private final AtomicLong yamlAtomicWrites = new AtomicLong();
    private final AtomicLong yamlAtomicWriteFailures = new AtomicLong();
    private final AtomicLong yamlHealthFlushCursor = new AtomicLong();
    private final AtomicBoolean healthCheckRunning = new AtomicBoolean(false);
    private final Map<String, AtomicInteger> queuedTasksByOwner = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Object, Boolean> sqlitePragmaInitialized = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<String, SimpleYamlStore> yamlStores = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, ManagedSqliteStore> sqliteStores = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, CloseRequest> closingPluginStores = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile HikariDataSource dataSource;
    private volatile ThreadPoolExecutor executor;
    private volatile StorageMode mode = StorageMode.YAML;
    private volatile String tablePrefix = "ledat";
    private volatile boolean healthy;
    private volatile String status = "not-started";
    private volatile String lastError = "";

    private static final class CloseRequest {
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private CompletableFuture<Void> completion() {
            return completion;
        }
    }

    public DefaultDatabaseProvider(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        reload();
    }

    @Override
    public synchronized void reload() {
        long shutdownDeadline = deadlineAfter(storageShutdownTimeoutMillis());
        flushDirtyYamlStores(true, shutdownDeadline);
        // Keep published YamlStore handles usable while the backing executor is
        // replaced. Writes submitted during the reload window run inline and
        // remain part of the same tracked store instead of becoming orphaned.
        parkYamlStores();
        closeLocalSqliteStores(shutdownDeadline);
        closeSqlOnly(shutdownDeadline);
        closeExecutor(shutdownDeadline);
        plugin.reloadConfig();
        this.mode = StorageMode.parse(plugin.getConfig().getString("storage.mode", "YAML"), StorageMode.YAML);
        this.tablePrefix = sanitizeIdentifier(plugin.getConfig().getString("storage.table-prefix", "ledat"));
        if (!mode.sql()) {
            this.executor = createExecutor(false);
            healthy = true;
            status = "YAML storage active";
            lastError = "";
            resumeManagedStores();
            return;
        }

        try {
            this.executor = createExecutor(true);
            HikariConfig config = baseHikariConfig("LeDatPlatform-" + mode.name(), mode == StorageMode.SQLITE);
            if (mode == StorageMode.SQLITE) {
                File dbFile = platformSqliteFile(plugin.getConfig().getString("storage.sqlite.file", "platform.db"));
                ensureParent(dbFile);
                config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
                config.setDriverClassName("org.sqlite.JDBC");
                config.setConnectionTestQuery("SELECT 1");
            } else {
                String host = plugin.getConfig().getString("storage.mysql.host", "127.0.0.1");
                int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
                String database = plugin.getConfig().getString("storage.mysql.database", "ledat_network");
                boolean ssl = plugin.getConfig().getBoolean("storage.mysql.ssl", false);
                config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useUnicode=true&characterEncoding=utf8&useSSL=" + ssl + "&serverTimezone=UTC&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true");
                config.setUsername(plugin.getConfig().getString("storage.mysql.username", "root"));
                config.setPassword(plugin.getConfig().getString("storage.mysql.password", ""));
                config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                applyMysqlProperties(config);
            }
            dataSource = new HikariDataSource(config);
            bootstrapTables();
            healthy = true;
            status = mode.name() + " connected";
            lastError = "";
        } catch (Throwable error) {
            closeSqlOnly(shutdownDeadline);
            closeExecutor(shutdownDeadline);
            // Platform SQL may be unavailable while per-plugin YAML/SQLite
            // stores still need a live worker after reload.
            this.executor = createExecutor(false);
            healthy = false;
            status = mode.name() + " disabled: " + safeError(error);
            lastError = safeError(error);
            logger.log(Level.WARNING, "DatabaseProvider could not start in " + mode + " mode", error);
        } finally {
            resumeManagedStores();
        }
    }

    private void parkYamlStores() {
        yamlStores.values().forEach(store -> store.rebind(DIRECT_EXECUTOR,
                plugin.getConfig().getBoolean("storage.yaml.write-through", false)));
    }

    private void resumeManagedStores() {
        boolean writeThrough = plugin.getConfig().getBoolean("storage.yaml.write-through", false);
        Executor target = executorOrDirect();
        yamlStores.values().forEach(store -> store.rebind(target, writeThrough));
        if (plugin.getConfig().getBoolean("storage.local-sqlite.enabled", true)) {
            sqliteStores.values().forEach(ManagedSqliteStore::reopen);
        }
    }

    private ThreadPoolExecutor createExecutor(boolean sqlMode) {
        int core = Math.max(1, plugin.getConfig().getInt("storage.executor.core-threads", 4));
        int max = Math.max(core, plugin.getConfig().getInt("storage.executor.max-threads", 12));
        int queue = Math.max(128, plugin.getConfig().getInt("storage.executor.queue-capacity", 8192));
        boolean scaleBeforeQueue = plugin.getConfig().getBoolean("storage.executor.scale-before-queue", true);
        int effectiveCore = scaleBeforeQueue ? max : core;
        AtomicLong threadId = new AtomicLong();
        ThreadFactory factory = runnable -> {
            // folia-audit: ignore raw-executor -- bounded database/file I/O worker; never calls Bukkit API directly.
            Thread thread = new Thread(runnable, "LeDatPlatform-DB-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, error) -> logger.log(Level.WARNING, "Uncaught database worker error", error));
            return thread;
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(effectiveCore, max, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(queue), factory, (runnable, executor) -> {
            rejected.incrementAndGet();
            throw new RejectedExecutionException("Database executor queue full: size=" + executor.getQueue().size() + ", capacity=" + queue);
        });
        pool.allowCoreThreadTimeOut(plugin.getConfig().getBoolean("storage.executor.allow-core-timeout", true));
        boolean prestart = plugin.getConfig().getBoolean("storage.executor.prestart-core-threads", true);
        boolean prestartLocal = plugin.getConfig().getBoolean("storage.executor.prestart-local-mode", false);
        if (prestart && (sqlMode || prestartLocal)) {
            pool.prestartAllCoreThreads();
        }
        return pool;
    }

    private HikariConfig baseHikariConfig(String poolName, boolean sqlite) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        int maxPool = Math.max(1, plugin.getConfig().getInt("storage.pool.maximum-pool-size", sqlite ? 1 : 10));
        if (sqlite) {
            maxPool = Math.max(1, plugin.getConfig().getInt("storage.local-sqlite.pool.maximum-pool-size", 1));
        }
        config.setMaximumPoolSize(maxPool);
        int minimumIdle = sqlite
                ? plugin.getConfig().getInt("storage.local-sqlite.pool.minimum-idle", Math.min(1, maxPool))
                : plugin.getConfig().getInt("storage.pool.minimum-idle", 1);
        config.setMinimumIdle(Math.min(maxPool, Math.max(0, minimumIdle)));
        long connectionTimeout = Math.max(1000L, plugin.getConfig().getLong(
                sqlite ? "storage.local-sqlite.pool.connection-timeout-ms" : "storage.pool.connection-timeout-ms", 5000L));
        long validationTimeout = Math.max(250L, plugin.getConfig().getLong(
                sqlite ? "storage.local-sqlite.pool.validation-timeout-ms" : "storage.pool.validation-timeout-ms", 3000L));
        validationTimeout = Math.min(validationTimeout, Math.max(250L, connectionTimeout - 1L));
        config.setConnectionTimeout(connectionTimeout);
        config.setValidationTimeout(validationTimeout);
        config.setIdleTimeout(Math.max(10000L, plugin.getConfig().getLong(sqlite ? "storage.local-sqlite.pool.idle-timeout-ms" : "storage.pool.idle-timeout-ms", 600000L)));
        config.setMaxLifetime(Math.max(30000L, plugin.getConfig().getLong(sqlite ? "storage.local-sqlite.pool.max-lifetime-ms" : "storage.pool.max-lifetime-ms", 1800000L)));
        config.setAutoCommit(true);
        long leak = plugin.getConfig().getLong(sqlite ? "storage.local-sqlite.pool.leak-detection-threshold-ms" : "storage.pool.leak-detection-threshold-ms", 0L);
        if (leak > 0L) {
            config.setLeakDetectionThreshold(leak);
        }
        return config;
    }

    private void applyMysqlProperties(HikariConfig config) {
        config.addDataSourceProperty("cachePrepStmts", plugin.getConfig().getBoolean("storage.mysql.cache-prepared-statements", true));
        config.addDataSourceProperty("prepStmtCacheSize", plugin.getConfig().getInt("storage.mysql.prepared-statement-cache-size", 250));
        config.addDataSourceProperty("prepStmtCacheSqlLimit", plugin.getConfig().getInt("storage.mysql.prepared-statement-cache-sql-limit", 2048));
        config.addDataSourceProperty("useServerPrepStmts", plugin.getConfig().getBoolean("storage.mysql.use-server-prepared-statements", true));
        config.addDataSourceProperty("useLocalSessionState", true);
        config.addDataSourceProperty("cacheServerConfiguration", true);
        config.addDataSourceProperty("elideSetAutoCommits", true);
        config.addDataSourceProperty("maintainTimeStats", false);
        config.addDataSourceProperty("tcpKeepAlive", plugin.getConfig().getBoolean("storage.mysql.tcp-keep-alive", true));
    }

    private void bootstrapTables() throws SQLException {
        if (!mode.sql()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            if (mode == StorageMode.SQLITE) {
                applySqlitePragmas(connection);
            }
            String auditSql = "CREATE TABLE IF NOT EXISTS " + tableName("platform", "audit_ledger") + " (" +
                    idColumn() + ", " +
                    "plugin_id VARCHAR(64) NOT NULL, category VARCHAR(64) NOT NULL, action VARCHAR(96) NOT NULL, " +
                    "actor VARCHAR(36), target VARCHAR(36), result VARCHAR(64), value_type VARCHAR(64), value TEXT, metadata TEXT, created_at BIGINT NOT NULL)";
            try (PreparedStatement statement = connection.prepareStatement(auditSql)) {
                statement.executeUpdate();
            }
            createIndex(connection, "idx_" + tablePrefix + "_audit_plugin_time", tableName("platform", "audit_ledger"), "plugin_id, created_at");
            createIndex(connection, "idx_" + tablePrefix + "_audit_category_time", tableName("platform", "audit_ledger"), "category, created_at");
        }
    }

    private void createIndex(Connection connection, String indexName, String tableName, String columns) {
        try (PreparedStatement statement = connection.prepareStatement("CREATE INDEX " + sanitizeIdentifier(indexName) + " ON " + tableName + " (" + columns + ")")) {
            statement.executeUpdate();
        } catch (SQLException duplicateOrUnsupported) {
            // MySQL/SQLite duplicate index syntax differs. Index is an optimization; table remains usable.
        }
    }

    private void applySqlitePragmas(Connection connection) {
        applySqlitePragmas(connection, false);
    }

    private void applySqlitePragmas(Connection connection, boolean localStore) {
        Object connectionIdentity = sqliteConnectionIdentity(connection);
        synchronized (sqlitePragmaInitialized) {
            if (sqlitePragmaInitialized.putIfAbsent(connectionIdentity, Boolean.TRUE) != null) {
                return;
            }
        }
        boolean initialized = false;
        Map<String, String> pragmas = new LinkedHashMap<>();
        pragmas.put("journal_mode", sqliteConfig(localStore, "journal-mode", "WAL"));
        pragmas.put("synchronous", sqliteSynchronous(sqliteConfig(localStore, "synchronous", "NORMAL")));
        pragmas.put("busy_timeout", String.valueOf(Math.max(1000, sqliteInt(localStore, "busy-timeout-ms", 5000))));
        pragmas.put("wal_autocheckpoint", String.valueOf(Math.max(100, sqliteInt(localStore, "wal-autocheckpoint", 1000))));
        pragmas.put("foreign_keys", plugin.getConfig().getBoolean("storage.sqlite.foreign-keys", true) ? "ON" : "OFF");
        pragmas.put("temp_store", sqliteTempStore(sqliteConfig(localStore, "temp-store", "MEMORY")));
        int cacheSize = sqliteInt(localStore, "cache-size", 10000);
        if (cacheSize != 0) {
            pragmas.put("cache_size", String.valueOf(cacheSize));
        }
        long journalSizeLimit = sqliteLong(localStore, "journal-size-limit-bytes", 67108864L);
        if (journalSizeLimit > 0L) {
            pragmas.put("journal_size_limit", String.valueOf(journalSizeLimit));
        }
        long mmap = sqliteLong(localStore, "mmap-size-bytes", 0L);
        if (mmap > 0L) {
            pragmas.put("mmap_size", String.valueOf(mmap));
        }
        try (Statement statement = connection.createStatement()) {
            for (Map.Entry<String, String> entry : pragmas.entrySet()) {
                try {
                    statement.execute("PRAGMA " + entry.getKey() + "=" + entry.getValue());
                } catch (SQLException error) {
                    if (plugin.getConfig().getBoolean("debug.enabled", false)) {
                        logger.fine("Could not apply SQLite PRAGMA " + entry.getKey() + " for " + (localStore ? "local" : "platform") + " store: " + safeError(error));
                    }
                }
            }
            initialized = true;
        } catch (SQLException error) {
            if (plugin.getConfig().getBoolean("debug.enabled", false)) {
                logger.fine("Could not create SQLite PRAGMA statement for " + (localStore ? "local" : "platform") + " store: " + safeError(error));
            }
        } finally {
            if (!initialized) {
                sqlitePragmaInitialized.remove(connectionIdentity);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object sqliteConnectionIdentity(Connection connection) {
        if (connection == null) {
            return this;
        }
        try {
            Class rawType = Class.forName("org.sqlite.SQLiteConnection", false, DefaultDatabaseProvider.class.getClassLoader());
            if (connection.isWrapperFor(rawType)) {
                Object unwrapped = connection.unwrap(rawType);
                if (unwrapped != null) {
                    return unwrapped;
                }
            }
        } catch (Throwable ignored) {
        }
        return connection;
    }

    private int sqliteInt(boolean localStore, String key, int fallback) {
        String localPath = "storage.local-sqlite.pragma." + key;
        if (localStore && plugin.getConfig().contains(localPath)) {
            return plugin.getConfig().getInt(localPath, fallback);
        }
        return plugin.getConfig().getInt("storage.sqlite." + key, fallback);
    }

    private long sqliteLong(boolean localStore, String key, long fallback) {
        String localPath = "storage.local-sqlite.pragma." + key;
        if (localStore && plugin.getConfig().contains(localPath)) {
            return plugin.getConfig().getLong(localPath, fallback);
        }
        return plugin.getConfig().getLong("storage.sqlite." + key, fallback);
    }

    private String sqliteConfig(boolean localStore, String key, String fallback) {
        String localPath = "storage.local-sqlite.pragma." + key;
        if (localStore && plugin.getConfig().contains(localPath)) {
            return plugin.getConfig().getString(localPath, fallback);
        }
        return plugin.getConfig().getString("storage.sqlite." + key, fallback);
    }

    private String sqliteSynchronous(String raw) {
        String value = raw == null ? "NORMAL" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "OFF", "NORMAL", "FULL", "EXTRA" -> value;
            default -> "NORMAL";
        };
    }

    private String sqliteTempStore(String raw) {
        String value = raw == null ? "MEMORY" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "DEFAULT", "FILE", "MEMORY" -> value;
            default -> "MEMORY";
        };
    }

    private String idColumn() {
        return mode == StorageMode.SQLITE ? "id INTEGER PRIMARY KEY AUTOINCREMENT" : "id BIGINT AUTO_INCREMENT PRIMARY KEY";
    }

    @Override public StorageMode mode() { return mode; }
    @Override public boolean enabled() { return mode == StorageMode.YAML || dataSource != null; }
    @Override public boolean healthy() { return healthy; }
    @Override public String status() { return status; }
    @Override public String lastError() { return lastError == null ? "" : lastError; }

    @Override
    public String tableName(String pluginId, String localName) {
        return tablePrefix + "_" + sanitizeIdentifier(pluginId) + "_" + sanitizeIdentifier(localName);
    }

    @Override
    public Connection borrowConnection(String requester) throws SQLException {
        if (!mode.sql() || dataSource == null) {
            throw new SQLException("SQL database is not enabled; current mode=" + mode);
        }
        guardMainThreadBorrow(requester);
        return dataSource.getConnection();
    }

    @Override
    public <T> CompletableFuture<T> query(String requester, SqlFunction<T> function) {
        return submit(requester, () -> {
            try (Connection connection = borrowConnection(requester)) {
                if (mode == StorageMode.SQLITE) {
                    applySqlitePragmas(connection);
                }
                return function.apply(connection);
            }
        });
    }

    @Override
    public CompletableFuture<Void> execute(String requester, SqlConsumer consumer) {
        return query(requester, connection -> {
            consumer.accept(connection);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> transaction(String requester, SqlConsumer consumer) {
        return submit(requester, () -> {
            try (Connection connection = borrowConnection(requester)) {
                if (mode == StorageMode.SQLITE) {
                    applySqlitePragmas(connection);
                }
                boolean old = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    consumer.accept(connection);
                    connection.commit();
                    return null;
                } catch (Throwable error) {
                    connection.rollback();
                    throw checked(error);
                } finally {
                    connection.setAutoCommit(old);
                }
            }
        });
    }

    public <T> CompletableFuture<T> submitExternal(String requester, Callable<T> callable) {
        if (callable == null) {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("callable"));
            return future;
        }
        return submit(requester == null || requester.isBlank() ? "external" : requester, callable::call);
    }

    private <T> CompletableFuture<T> submit(String requester, ThrowingSupplier<T> supplier) {
        return submitOwned(requesterOwner(requester), requester, supplier);
    }

    private <T> CompletableFuture<T> submitOwned(String ownerKey, String requester, ThrowingSupplier<T> supplier) {
        submitted.incrementAndGet();
        CompletableFuture<T> future = new CompletableFuture<>();
        ThreadPoolExecutor target = executor;
        if (target == null || target.isShutdown()) {
            failed.incrementAndGet();
            future.completeExceptionally(new IllegalStateException("Database executor is not running"));
            return future;
        }
        int ownerLimit = ownerQueueLimit(ownerKey, target);
        if (!admitOwner(ownerKey, ownerLimit)) {
            RejectedExecutionException rejection = new RejectedExecutionException(
                    "Database owner queue full: owner=" + ownerKey + ", capacity=" + ownerLimit);
            rejected.incrementAndGet();
            failed.incrementAndGet();
            lastError = safeError(rejection);
            future.completeExceptionally(rejection);
            return future;
        }
        maybeWarnQueue(target);
        long enqueuedAt = System.nanoTime();
        try {
            target.execute(new DatabaseTask<>(ownerKey, requester, supplier, future, enqueuedAt));
        } catch (RejectedExecutionException rejection) {
            releaseOwner(ownerKey);
            failed.incrementAndGet();
            lastError = safeError(rejection);
            future.completeExceptionally(rejection);
        }
        return future;
    }

    private boolean admitOwner(String ownerKey, int limit) {
        AtomicBoolean admitted = new AtomicBoolean();
        queuedTasksByOwner.compute(ownerKey, (ignored, queued) -> {
            AtomicInteger current = queued == null ? new AtomicInteger() : queued;
            if (current.get() < limit) {
                current.incrementAndGet();
                admitted.set(true);
            }
            return current;
        });
        return admitted.get();
    }

    private void releaseOwner(String ownerKey) {
        queuedTasksByOwner.computeIfPresent(ownerKey, (ignored, queued) -> {
            int remaining = queued.decrementAndGet();
            return remaining <= 0 ? null : queued;
        });
    }

    private final class DatabaseTask<T> implements Runnable {
        private final String ownerKey;
        private final String requester;
        private final ThrowingSupplier<T> supplier;
        private final CompletableFuture<T> future;
        private final long enqueuedAt;
        private final AtomicBoolean terminal = new AtomicBoolean();

        private DatabaseTask(String ownerKey, String requester, ThrowingSupplier<T> supplier,
                             CompletableFuture<T> future, long enqueuedAt) {
            this.ownerKey = ownerKey;
            this.requester = requester;
            this.supplier = supplier;
            this.future = future;
            this.enqueuedAt = enqueuedAt;
        }

        @Override
        public void run() {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            releaseOwner(ownerKey);
            recordQueueWait(ownerKey, requester, enqueuedAt);
            long start = System.currentTimeMillis();
            try {
                T result = supplier.get();
                completed.incrementAndGet();
                warnSlow(requester, start);
                future.complete(result);
            } catch (Throwable error) {
                failed.incrementAndGet();
                lastError = safeError(error);
                future.completeExceptionally(error instanceof RuntimeException ? error : new RuntimeException(error));
            }
        }

        private void rejectBeforeStart(String reason) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            releaseOwner(ownerKey);
            RejectedExecutionException rejection = new RejectedExecutionException(reason + ": " + requester);
            failed.incrementAndGet();
            lastError = safeError(rejection);
            future.completeExceptionally(rejection);
        }
    }

    private int ownerQueueLimit(String ownerKey, ThreadPoolExecutor target) {
        int fallback = Math.max(1, plugin.getConfig().getInt("database.query.default-plugin-max-queued-tasks", 2048));
        int configured = Math.max(1, plugin.getConfig().getInt("database.plugins." + ownerKey + ".max-queued-tasks", fallback));
        int fairShareCeiling = Math.max(1, (target.getQueue().size() + target.getQueue().remainingCapacity()) / 2);
        return Math.min(configured, fairShareCeiling);
    }

    private String requesterOwner(String requester) {
        String normalized = sanitizeIdentifier(requester == null || requester.isBlank() ? "external" : requester.replace(':', '_'));
        ConfigurationSection policies = plugin.getConfig().getConfigurationSection("database.plugins");
        if (policies != null) {
            String matched = "";
            for (String candidate : policies.getKeys(false)) {
                String safeCandidate = sanitizeIdentifier(candidate);
                if ((normalized.equals(safeCandidate) || normalized.startsWith(safeCandidate + "_"))
                        && safeCandidate.length() > matched.length()) {
                    matched = safeCandidate;
                }
            }
            if (!matched.isBlank()) {
                return matched;
            }
        }
        int separator = requester == null ? -1 : requester.indexOf(':');
        return separator > 0 ? sanitizeIdentifier(requester.substring(0, separator)) : normalized;
    }

    private void maybeWarnQueue(ThreadPoolExecutor pool) {
        int warnAt = Math.max(0, plugin.getConfig().getInt("storage.executor.warn-queue-size", 2048));
        int queued = pool.getQueue().size();
        if (warnAt <= 0 || queued < warnAt) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = queueWarnings.get();
        if (now - previous > 30000L && queueWarnings.compareAndSet(previous, now)) {
            logger.warning("Database executor queue is high: queued=" + queued + ", active=" + pool.getActiveCount() + ". Consider increasing storage.executor.* or checking slow queries.");
        }
    }

    private void recordQueueWait(String ownerKey, String requester, long enqueuedAt) {
        long waited = Math.max(0L, System.nanoTime() - enqueuedAt);
        queueWaitSamples.incrementAndGet();
        totalQueueWaitNanos.addAndGet(waited);
        maxQueueWaitNanos.accumulateAndGet(waited, Math::max);
        long thresholdMillis = Math.max(0L, plugin.getConfig().getLong("storage.executor.queue-wait-warn-ms", 250L));
        if (thresholdMillis <= 0L || waited < TimeUnit.MILLISECONDS.toNanos(thresholdMillis)) {
            return;
        }
        long now = System.currentTimeMillis();
        long interval = Math.max(1000L, plugin.getConfig().getLong("storage.executor.queue-wait-log-interval-ms", 10000L));
        long previous = queueWaitWarningAt.get();
        if (now - previous >= interval && queueWaitWarningAt.compareAndSet(previous, now)) {
            logger.warning("Database task waited in queue: owner=" + ownerKey + ", requester=" + requester
                    + ", wait=" + TimeUnit.NANOSECONDS.toMillis(waited) + "ms");
        }
    }

    long queueWaitSamples() {
        return queueWaitSamples.get();
    }

    long maxQueueWaitMillis() {
        return TimeUnit.NANOSECONDS.toMillis(maxQueueWaitNanos.get());
    }

    long averageQueueWaitMillis() {
        long samples = queueWaitSamples.get();
        return samples == 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(totalQueueWaitNanos.get() / samples);
    }

    @Override
    public Optional<YamlStore> yamlStore(String pluginId) {
        String safePluginId = sanitizeIdentifier(pluginId);
        Optional<File> logStoreFile = platformLogStoreFile(safePluginId);
        if (logStoreFile.isPresent()) {
            File file = logStoreFile.get();
            String key = "platform-log:" + safePluginId + ":" + file.getPath();
            return Optional.of(yamlStores.computeIfAbsent(key, ignored -> new SimpleYamlStore(plugin, "ledat_platform", safePluginId, file, executorOrDirect(), plugin.getConfig().getBoolean("storage.yaml.write-through", false))));
        }
        Plugin owner = Bukkit.getPluginManager().getPlugin(pluginId == null ? "" : pluginId);
        if (owner != null && plugin.getConfig().getBoolean("storage.standard-layout.enabled", true)) {
            return yamlStore(owner, plugin.getConfig().getString("storage.standard-layout.default-yaml-file", "data"));
        }
        return Optional.of(yamlStores.computeIfAbsent("platform:" + safePluginId, ignored -> new SimpleYamlStore(plugin, safePluginId, safePluginId, executorOrDirect(), plugin.getConfig().getBoolean("storage.yaml.write-through", false))));
    }

    @Override
    public File storageFolder(Plugin owner) {
        Plugin target = owner == null ? plugin : owner;
        String folderName = sanitizeFileSegment(plugin.getConfig().getString("storage.standard-layout.folder", "storage"));
        File folder = new File(target.getDataFolder(), folderName);
        if (!folder.exists() && !folder.mkdirs()) {
            logger.warning("Could not create storage folder: " + folder.getPath());
        }
        return folder;
    }

    @Override
    public Optional<YamlStore> yamlStore(Plugin owner, String storeName) {
        if (owner == null) {
            return Optional.empty();
        }
        String pluginId = sanitizeIdentifier(owner.getName());
        String name = normalizeStoreName(storeName, plugin.getConfig().getString("storage.standard-layout.default-yaml-file", "data"));
        String key = "yaml:" + pluginId + ":" + name;
        return Optional.of(yamlStores.computeIfAbsent(key, ignored -> new SimpleYamlStore(owner, pluginId, name, executorOrDirect(), plugin.getConfig().getBoolean("storage.yaml.write-through", false))));
    }

    @Override
    public Optional<SqliteStore> sqliteStore(Plugin owner, String databaseName) {
        if (owner == null || !plugin.getConfig().getBoolean("storage.local-sqlite.enabled", true)) {
            return Optional.empty();
        }
        String pluginId = sanitizeIdentifier(owner.getName());
        String name = normalizeStoreName(databaseName, plugin.getConfig().getString("storage.standard-layout.default-sqlite-file", "data"));
        String key = "sqlite:" + pluginId + ":" + name;
        AtomicBoolean created = new AtomicBoolean();
        ManagedSqliteStore store = sqliteStores.computeIfAbsent(key, ignored -> {
            created.set(true);
            return new ManagedSqliteStore(owner, name);
        });
        if (created.get()) {
            long ownerStores = sqliteStores.values().stream()
                    .filter(candidate -> candidate.pluginId().equals(pluginId))
                    .count();
            if (ownerStores == LOCAL_SQLITE_STORE_WARNING_THRESHOLD + 1L) {
                logger.warning("Plugin " + owner.getName() + " owns more than "
                        + LOCAL_SQLITE_STORE_WARNING_THRESHOLD
                        + " local SQLite pools; review aggregate connection and checkpoint cost.");
            }
        }
        return Optional.of(store);
    }

    @Override
    public Map<String, StorageUnit> storageUnits() {
        Map<String, StorageUnit> units = new LinkedHashMap<>();
        yamlStores.forEach((key, store) -> units.put(key, store.unit(key)));
        sqliteStores.forEach((key, store) -> units.put(key, store.unit(key)));
        return Map.copyOf(units);
    }

    @Override
    public CompletableFuture<Void> flush(Plugin owner) {
        if (owner == null) {
            return CompletableFuture.completedFuture(null);
        }
        String pluginId = sanitizeIdentifier(owner.getName());
        CompletableFuture<?>[] futures = yamlStores.entrySet().stream()
                .filter(entry -> entry.getValue().pluginId().equals(pluginId))
                .map(entry -> entry.getValue().saveDirtyOnly())
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    @Override
    public CompletableFuture<Void> flushAll() {
        CompletableFuture<?>[] futures = yamlStores.values().stream()
                .map(SimpleYamlStore::saveDirtyOnly)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }


    @Override
    public CompletableFuture<Void> checkpoint(Plugin owner) {
        if (owner == null) {
            return CompletableFuture.completedFuture(null);
        }
        String pluginId = sanitizeIdentifier(owner.getName());
        CompletableFuture<?>[] futures = sqliteStores.values().stream()
                .filter(store -> store.pluginId().equals(pluginId))
                .map(store -> store.checkpoint("manual"))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    @Override
    public CompletableFuture<Void> checkpointAll() {
        CompletableFuture<?>[] futures = sqliteStores.values().stream()
                .map(store -> store.checkpoint("manual-all"))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    @Override
    public void closePlugin(String pluginId) {
        closePlugin(pluginId, storageShutdownTimeoutMillis());
    }

    @Override
    public CompletableFuture<Void> closePluginAsync(String pluginId) {
        return closePluginAfter(pluginId, CompletableFuture.completedFuture(null));
    }

    @Override
    public CompletableFuture<Void> closePluginAfter(String pluginId, CompletionStage<?> persistenceBarrier) {
        String safePluginId = sanitizeIdentifier(pluginId);
        CloseRequest requested = new CloseRequest();
        CloseRequest existing = closingPluginStores.putIfAbsent(safePluginId, requested);
        if (existing != null) {
            return existing.completion();
        }

        CompletionStage<?> barrier = persistenceBarrier == null
                ? CompletableFuture.completedFuture(null) : persistenceBarrier;
        barrier.handle((ignored, error) -> {
            if (error != null) {
                logger.warning("Plugin storage barrier failed before close for " + safePluginId + ": " + safeError(error));
            }
            return null;
        }).thenCompose(ignored -> closePluginNowAsync(safePluginId)).whenComplete((ignored, error) -> {
            closingPluginStores.remove(safePluginId, requested);
            if (error == null) {
                requested.completion().complete(null);
            } else {
                requested.completion().completeExceptionally(error);
            }
        });
        return requested.completion();
    }

    private CompletableFuture<Void> closePluginNowAsync(String safePluginId) {
        java.util.List<CompletableFuture<Void>> closes = new java.util.ArrayList<>();
        for (Map.Entry<String, SimpleYamlStore> entry : yamlStores.entrySet()) {
            if (!entry.getValue().pluginId().equals(safePluginId)) {
                continue;
            }
            closes.add(entry.getValue().saveDirtyOnly().whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.warning("YAML store close flush failed for " + safePluginId + ": " + safeError(error));
                }
                yamlStores.remove(entry.getKey(), entry.getValue());
            }));
        }
        for (Map.Entry<String, ManagedSqliteStore> entry : sqliteStores.entrySet()) {
            if (!entry.getValue().pluginId().equals(safePluginId)) {
                continue;
            }
            ManagedSqliteStore store = entry.getValue();
            closes.add(store.checkpoint("close-plugin").whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.warning("SQLite checkpoint failed for " + safePluginId + ": " + safeError(error));
                }
                store.close();
                sqliteStores.remove(entry.getKey(), store);
            }));
        }
        return CompletableFuture.allOf(closes.toArray(CompletableFuture[]::new));
    }

    public void closePlugin(String pluginId, long totalTimeoutMillis) {
        String safePluginId = sanitizeIdentifier(pluginId);
        CloseRequest requested = closingPluginStores.get(safePluginId);
        if (requested != null) {
            awaitPluginClose(safePluginId, requested.completion(), totalTimeoutMillis);
            return;
        }
        long deadline = deadlineAfter(totalTimeoutMillis);
        for (Map.Entry<String, SimpleYamlStore> entry : yamlStores.entrySet()) {
            if (entry.getValue().pluginId().equals(safePluginId)) {
                try {
                    CompletableFuture<Void> save = entry.getValue().saveDirtyOnly();
                    long remaining = remainingMillis(deadline);
                    if (remaining > 0L) {
                        save.get(remaining, TimeUnit.MILLISECONDS);
                    } else if (!save.isDone()) {
                        warnShutdownDeadline("YAML close flush", safePluginId);
                        logAsyncFailure(save, "YAML store close flush failed for " + safePluginId);
                    }
                } catch (Throwable error) {
                    logger.warning("YAML store close flush failed for " + safePluginId + ": " + safeError(error));
                }
                yamlStores.remove(entry.getKey());
            }
        }
        for (Map.Entry<String, ManagedSqliteStore> entry : sqliteStores.entrySet()) {
            if (entry.getValue().pluginId().equals(safePluginId)) {
                try {
                    long remaining = remainingMillis(deadline);
                    if (remaining > 0L) {
                        CompletableFuture<Void> checkpoint = entry.getValue().checkpoint("close-plugin");
                        checkpoint.get(remaining, TimeUnit.MILLISECONDS);
                    } else {
                        warnShutdownDeadline("SQLite checkpoint", safePluginId);
                    }
                } catch (Throwable error) {
                    logger.warning("SQLite checkpoint failed for " + safePluginId + ": " + safeError(error));
                }
                entry.getValue().close();
                sqliteStores.remove(entry.getKey());
            }
        }
    }

    private void awaitPluginClose(String pluginId, CompletableFuture<Void> close, long timeoutMillis) {
        try {
            close.get(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (Throwable error) {
            logger.warning("Asynchronous plugin storage close did not complete for " + pluginId + ": " + safeError(error));
        }
    }


    public long sqliteCheckpoints() { return sqliteCheckpoints.get(); }
    public long sqliteCheckpointFailures() { return sqliteCheckpointFailures.get(); }
    public long sqliteQuickCheckFailures() { return sqliteQuickCheckFailures.get(); }
    public long sqliteIntegrityCheckFailures() { return sqliteIntegrityCheckFailures.get(); }
    public long storageVerifyRuns() { return storageVerifyRuns.get(); }
    public long storageVerifyFailures() { return storageVerifyFailures.get(); }
    public long yamlAtomicWrites() { return yamlAtomicWrites.get(); }
    public long yamlAtomicWriteFailures() { return yamlAtomicWriteFailures.get(); }

    @Override
    public CompletableFuture<StorageVerificationResult> verifyStorage(Plugin owner, boolean deep) {
        String scope = owner == null ? "all" : owner.getName();
        boolean effectiveDeep = deep || plugin.getConfig().getBoolean("storage.verification.deep-integrity-check", false);
        return submit("storage-verify:" + scope, () -> verifyStorageDirect(owner, effectiveDeep));
    }

    private StorageVerificationResult verifyStorageDirect(Plugin owner, boolean deep) {
        long started = System.currentTimeMillis();
        storageVerifyRuns.incrementAndGet();
        String ownerKey = owner == null ? "" : sanitizeIdentifier(owner.getName());
        java.util.List<String> details = new java.util.ArrayList<>();
        java.util.List<String> failures = new java.util.ArrayList<>();
        int checked = 0;
        int ok = 0;
        int warnings = 0;
        int errors = 0;

        if (owner == null && mode.sql()) {
            checked++;
            try (Connection connection = borrowConnection("platform-storage-verify"); PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
                if (mode == StorageMode.SQLITE) {
                    applySqlitePragmas(connection);
                    runSqliteIntegrityCheck(connection, deep, "platform");
                    if (plugin.getConfig().getBoolean("storage.verification.checkpoint-sqlite", false)) {
                        checkpointPlatformSqlite(connection, "verify");
                    }
                }
                statement.execute();
                ok++;
                details.add("OK platform-sql mode=" + mode);
            } catch (Throwable error) {
                errors++;
                storageVerifyFailures.incrementAndGet();
                String safe = safeError(error);
                failures.add("platform-sql: " + safe);
                lastError = safe;
            }
        }

        boolean flushDirty = plugin.getConfig().getBoolean("storage.verification.flush-dirty-yaml", true);
        boolean warnDirty = plugin.getConfig().getBoolean("storage.verification.warn-dirty-yaml", true);
        for (SimpleYamlStore store : yamlStores.values()) {
            if (!ownerKey.isBlank() && !store.pluginId().equals(ownerKey)) {
                continue;
            }
            checked++;
            try {
                if (warnDirty && store.dirty()) {
                    warnings++;
                    details.add("WARN yaml " + store.pluginId() + "/" + store.name() + " dirty=true");
                }
                if (flushDirty && store.dirty()) {
                    store.saveDirtyOnly().get(storageShutdownTimeoutMillis(), TimeUnit.MILLISECONDS);
                }
                File parent = store.file().getParentFile();
                if (parent != null && (!parent.exists() || !parent.canWrite())) {
                    warnings++;
                    details.add("WARN yaml " + store.pluginId() + "/" + store.name() + " parent-not-writable=" + store.file().getPath());
                } else {
                    ok++;
                    details.add("OK yaml " + store.pluginId() + "/" + store.name());
                }
            } catch (Throwable error) {
                errors++;
                storageVerifyFailures.incrementAndGet();
                String safe = safeError(error);
                failures.add("yaml " + store.pluginId() + "/" + store.name() + ": " + safe);
            }
        }

        for (ManagedSqliteStore store : sqliteStores.values()) {
            if (!ownerKey.isBlank() && !store.pluginId().equals(ownerKey)) {
                continue;
            }
            checked++;
            try {
                String result = store.verifyDirect(deep, plugin.getConfig().getBoolean("storage.verification.checkpoint-sqlite", false));
                ok++;
                details.add("OK sqlite " + store.pluginId() + "/" + store.name() + " " + result);
            } catch (Throwable error) {
                errors++;
                storageVerifyFailures.incrementAndGet();
                String safe = safeError(error);
                failures.add("sqlite " + store.pluginId() + "/" + store.name() + ": " + safe);
            }
        }

        if (checked == 0) {
            warnings++;
            details.add("WARN no opened storage units for scope=" + (owner == null ? "all" : owner.getName()));
        }
        String summary = "checked=" + checked + ", ok=" + ok + ", warn=" + warnings + ", error=" + errors + ", took=" + (System.currentTimeMillis() - started) + "ms";
        return new StorageVerificationResult(owner == null ? "all" : owner.getName(), deep, checked, ok, warnings, errors, java.util.List.copyOf(details), java.util.List.copyOf(failures), summary);
    }

    private void runSqliteIntegrityCheck(Connection connection, boolean deep, String scope) throws SQLException {
        String pragma = deep ? "integrity_check" : "quick_check";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA " + pragma)) {
            while (result.next()) {
                String row = result.getString(1);
                if (row != null && !row.equalsIgnoreCase("ok")) {
                    if (deep) {
                        sqliteIntegrityCheckFailures.incrementAndGet();
                    } else {
                        sqliteQuickCheckFailures.incrementAndGet();
                    }
                    throw new SQLException("SQLite " + pragma + " failed for " + scope + ": " + row);
                }
            }
        }
    }

    @Override
    public DatabaseStats stats() {
        int active = 0;
        int idle = 0;
        int waiting = 0;
        if (dataSource != null) {
            try {
                active = dataSource.getHikariPoolMXBean().getActiveConnections();
                idle = dataSource.getHikariPoolMXBean().getIdleConnections();
                waiting = dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection();
            } catch (Throwable ignored) {
            }
        }
        ThreadPoolExecutor pool = executor;
        int queued = pool == null ? 0 : pool.getQueue().size();
        int size = pool == null ? 0 : pool.getPoolSize();
        return new DatabaseStats(mode, enabled(), healthy, active, idle, waiting, queued, size, submitted.get(), completed.get(), failed.get(), rejected.get(), slow.get(), yamlStores.size(), sqliteStores.size(), storageRootPath(), lastError());
    }

    @Override
    public void healthCheck() {
        if (!healthCheckRunning.compareAndSet(false, true)) {
            return;
        }
        // Always flush dirty managed YAML stores, even when the platform SQL provider is active.
        // Child plugins may legitimately mix shared SQL with lightweight local YAML settings.
        flushDirtyYamlStores(false, Long.MAX_VALUE);
        submit("platform-health", () -> {
            healthCheckDirect();
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                healthy = false;
                lastError = safeError(error);
                status = mode.name() + " unhealthy: " + safeError(error);
            }
            healthCheckRunning.set(false);
        });
    }

    private void healthCheckDirect() {
        if (!mode.sql()) {
            healthy = true;
            status = "YAML storage active @ " + Instant.now();
        } else {
            try (Connection connection = borrowConnection("platform-health"); PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
                if (mode == StorageMode.SQLITE) {
                    applySqlitePragmas(connection);
                }
                statement.execute();
                if (mode == StorageMode.SQLITE && plugin.getConfig().getBoolean("storage.sqlite.checkpoint-on-health-check", false)) {
                    checkpointPlatformSqlite(connection, "health");
                }
                healthy = true;
                status = mode.name() + " healthy @ " + Instant.now();
                lastError = "";
            } catch (Throwable error) {
                healthy = false;
                lastError = safeError(error);
                status = mode.name() + " unhealthy: " + safeError(error);
            }
        }
        sqliteStores.values().forEach(ManagedSqliteStore::healthCheck);
    }

    @Override
    public synchronized void close() {
        long shutdownDeadline = deadlineAfter(storageShutdownTimeoutMillis());
        flushDirtyYamlStores(true, shutdownDeadline);
        closeLocalSqliteStores(shutdownDeadline);
        closeSqlOnly(shutdownDeadline);
        closeExecutor(shutdownDeadline);
        healthy = false;
    }

    private void checkpointPlatformSqlite(String reason) {
        HikariDataSource source = dataSource;
        if (source == null) {
            return;
        }
        try (Connection connection = source.getConnection()) {
            checkpointPlatformSqlite(connection, reason);
        } catch (Throwable error) {
            sqliteCheckpointFailures.incrementAndGet();
            lastError = safeError(error);
            logger.warning("Platform SQLite checkpoint failed during " + reason + ": " + safeError(error));
        }
    }

    private void checkpointPlatformSqlite(Connection connection, String reason) throws SQLException {
        String checkpointMode = reason != null && reason.contains("close")
                ? plugin.getConfig().getString("storage.sqlite.close-checkpoint-mode", "TRUNCATE")
                : plugin.getConfig().getString("storage.sqlite.checkpoint-mode", "PASSIVE");
        applySqlitePragmas(connection);
        if (plugin.getConfig().getBoolean("storage.sqlite.optimize-on-checkpoint", true)) {
            try (Statement optimize = connection.createStatement()) {
                optimize.execute("PRAGMA optimize");
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(" + normalizeCheckpointMode(checkpointMode) + ")");
            sqliteCheckpoints.incrementAndGet();
        }
    }

    private String normalizeCheckpointMode(String raw) {
        String value = raw == null ? "PASSIVE" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "PASSIVE", "FULL", "RESTART", "TRUNCATE" -> value;
            default -> "PASSIVE";
        };
    }

    private synchronized void closeSqlOnly(long deadline) {
        if (dataSource != null) {
            if (mode == StorageMode.SQLITE && plugin.getConfig().getBoolean("storage.sqlite.checkpoint-on-close", true)) {
                checkpointPlatformSqliteWithinDeadline(deadline);
            }
            try {
                dataSource.close();
            } catch (Throwable ignored) {
            }
            dataSource = null;
        }
    }

    private void checkpointPlatformSqliteWithinDeadline(long deadline) {
        long remaining = remainingMillis(deadline);
        ThreadPoolExecutor target = executor;
        if (remaining <= 0L || target == null || target.isShutdown()) {
            warnShutdownDeadline("Platform SQLite checkpoint", "platform");
            return;
        }
        Future<?> checkpoint = null;
        try {
            checkpoint = target.submit(() -> checkpointPlatformSqlite("close"));
            checkpoint.get(remaining, TimeUnit.MILLISECONDS);
        } catch (Throwable error) {
            if (checkpoint != null) {
                checkpoint.cancel(true);
            }
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warning("Platform SQLite checkpoint did not complete within the shared storage shutdown deadline: " + safeError(error));
        }
    }

    private synchronized void closeLocalSqliteStores(long deadline) {
        for (ManagedSqliteStore store : sqliteStores.values()) {
            try {
                long remaining = remainingMillis(deadline);
                if (remaining > 0L) {
                    CompletableFuture<Void> checkpoint = store.checkpoint("close");
                    checkpoint.get(remaining, TimeUnit.MILLISECONDS);
                } else {
                    warnShutdownDeadline("SQLite checkpoint", store.pluginId() + "/" + store.name());
                }
            } catch (Throwable error) {
                logger.warning("SQLite checkpoint failed before close for " + store.pluginId() + "/" + store.name() + ": " + safeError(error));
            }
            try {
                store.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private synchronized void closeExecutor(long deadline) {
        ThreadPoolExecutor target = executor;
        if (target != null) {
            target.shutdown();
            long timeout = remainingMillis(deadline);
            try {
                if (timeout <= 0L || !target.awaitTermination(timeout, TimeUnit.MILLISECONDS)) {
                    rejectQueuedTasks(target.shutdownNow());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                rejectQueuedTasks(target.shutdownNow());
            }
            executor = null;
        }
    }

    private void rejectQueuedTasks(java.util.List<Runnable> queuedTasks) {
        for (Runnable queued : queuedTasks) {
            if (queued instanceof DefaultDatabaseProvider.DatabaseTask<?> databaseTask) {
                databaseTask.rejectBeforeStart("Database executor stopped before queued task started");
            } else if (queued instanceof Future<?> future) {
                future.cancel(false);
            }
        }
    }

    private void flushDirtyYamlStores(boolean blocking, long deadline) {
        java.util.List<SimpleYamlStore> stores = dirtyYamlStores();
        if (stores.isEmpty()) {
            return;
        }
        if (!blocking) {
            stores = limitedHealthYamlFlushStores(stores);
        }
        if (stores.size() > 20) {
            CompletableFuture<?>[] futures = stores.stream()
                    .map(store -> store.saveDirtyOnly().exceptionally(error -> {
                        logger.warning("YAML store parallel flush failed for " + store.pluginId() + "/" + store.name() + ": " + safeError(error));
                        return null;
                    }))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture<Void> allOf = CompletableFuture.allOf(futures);
            if (blocking) {
                try {
                    long remaining = remainingMillis(deadline);
                    if (remaining > 0L) {
                        allOf.get(remaining, TimeUnit.MILLISECONDS);
                    } else if (!allOf.isDone()) {
                        warnShutdownDeadline("Parallel YAML flush", stores.size() + " stores");
                    }
                } catch (Throwable error) {
                    logger.warning("Parallel YAML store flush wait failed: " + safeError(error));
                }
            }
            return;
        }
        for (SimpleYamlStore store : stores) {
            try {
                CompletableFuture<Void> future = store.saveDirtyOnly();
                if (blocking) {
                    long remaining = remainingMillis(deadline);
                    if (remaining > 0L) {
                        future.get(remaining, TimeUnit.MILLISECONDS);
                    } else if (!future.isDone()) {
                        warnShutdownDeadline("YAML flush", store.pluginId() + "/" + store.name());
                        logAsyncFailure(future, "YAML store flush failed for " + store.pluginId() + "/" + store.name());
                    }
                }
            } catch (Throwable error) {
                logger.warning("YAML store flush failed: " + safeError(error));
            }
        }
    }

    private void warnShutdownDeadline(String operation, String target) {
        logger.warning(operation + " skipped waiting because the shared storage shutdown deadline was exhausted: " + target);
    }

    private void logAsyncFailure(CompletableFuture<Void> future, String message) {
        future.whenComplete((ignored, error) -> {
            if (error != null) {
                logger.warning(message + ": " + safeError(error));
            }
        });
    }

    private java.util.List<SimpleYamlStore> dirtyYamlStores() {
        java.util.List<SimpleYamlStore> dirtyStores = new java.util.ArrayList<>();
        for (SimpleYamlStore store : yamlStores.values()) {
            if (store.dirty()) {
                dirtyStores.add(store);
            }
        }
        return dirtyStores;
    }

    private java.util.List<SimpleYamlStore> limitedHealthYamlFlushStores(java.util.List<SimpleYamlStore> dirtyStores) {
        int limit = Math.max(0, plugin.getConfig().getInt("storage.yaml.health-flush-max-stores", 8));
        if (limit <= 0 || dirtyStores.size() <= limit) {
            return dirtyStores;
        }
        java.util.List<SimpleYamlStore> selected = new java.util.ArrayList<>(limit);
        int start = (int) Math.floorMod(yamlHealthFlushCursor.getAndAdd(limit), dirtyStores.size());
        for (int offset = 0; offset < dirtyStores.size() && selected.size() < limit; offset++) {
            selected.add(dirtyStores.get((start + offset) % dirtyStores.size()));
        }
        return selected;
    }

    private long storageShutdownTimeoutMillis() {
        long legacySeconds = Math.max(1L, plugin.getConfig()
                .getLong("storage.executor.shutdown-timeout-seconds", 3L));
        long cappedLegacyMillis = Math.min(legacySeconds * 1000L, 3000L);
        return Math.max(250L, plugin.getConfig()
                .getLong("storage.executor.shutdown-timeout-ms", cappedLegacyMillis));
    }

    private long deadlineAfter(long timeoutMillis) {
        long safeMillis = Math.max(0L, timeoutMillis);
        long now = System.nanoTime();
        long nanos = TimeUnit.MILLISECONDS.toNanos(safeMillis);
        return Long.MAX_VALUE - now < nanos ? Long.MAX_VALUE : now + nanos;
    }

    private long remainingMillis(long deadline) {
        if (deadline == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long remaining = deadline - System.nanoTime();
        return remaining <= 0L ? 0L : Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private void warnSlow(String requester, long start) {
        long took = System.currentTimeMillis() - start;
        long threshold = plugin.getConfig().getLong("storage.query.slow-query-warn-ms", 250L);
        if (threshold > 0L && took >= threshold) {
            slow.incrementAndGet();
            long interval = Math.max(1000L, plugin.getConfig().getLong("storage.query.slow-query-log-interval-ms", 10000L));
            long now = System.currentTimeMillis();
            long previous = slowWarningAt.get();
            if (now - previous >= interval && slowWarningAt.compareAndSet(previous, now)) {
                logger.warning("Slow DB task by " + requester + ": " + took + "ms. Further slow DB logs are rate-limited for " + interval + "ms.");
            }
        }
    }

    private void guardMainThreadBorrow(String requester) throws SQLException {
        // Bukkit has no server singleton in standalone integration tests and
        // tooling. In a live server the normal primary-thread guard applies.
        if (Bukkit.getServer() == null || !Bukkit.isPrimaryThread()) {
            return;
        }
        String message = "Main-thread DB borrow by " + requester + ". Move this query async.";
        boolean production = plugin.getConfig().getBoolean("production-profile.enabled", false);
        boolean reject = plugin.getConfig().getBoolean("storage.safety.reject-main-thread-borrows", false)
                || (production && plugin.getConfig().getBoolean("production-profile.database.reject-main-thread-borrows", true));
        if (reject) {
            throw new SQLException(message);
        }
        if (production || plugin.getConfig().getBoolean("storage.safety.log-main-thread-borrows", true)) {
            long interval = Math.max(1000L, plugin.getConfig().getLong("storage.safety.main-thread-borrow-warning-interval-ms", 60000L));
            long now = System.currentTimeMillis();
            long previous = mainThreadBorrowWarningAt.get();
            if (now - previous >= interval && mainThreadBorrowWarningAt.compareAndSet(previous, now)) {
                logger.warning(message + " Further warnings are rate-limited for " + interval + "ms.");
            }
        }
    }

    private File platformSqliteFile(String rawName) {
        String name = rawName == null || rawName.isBlank() ? "platform.db" : rawName.trim();
        if (name.contains("/") || name.contains("\\")) {
            name = sanitizeFileSegment(new File(name).getName());
        }
        return new File(plugin.getDataFolder(), name);
    }

    private File sqliteFile(Plugin owner, String name) {
        String normalized = normalizeStoreName(name, plugin.getConfig().getString("storage.standard-layout.default-sqlite-file", "data"));
        String extension = plugin.getConfig().getString("storage.local-sqlite.extension", ".db");
        if (extension == null || extension.isBlank()) {
            extension = ".db";
        }
        if (!extension.startsWith(".")) {
            extension = "." + extension;
        }
        return new File(storageFolder(owner), normalized + extension.toLowerCase(java.util.Locale.ROOT));
    }

    private Optional<File> platformLogStoreFile(String safePluginId) {
        if ("platform_audit".equals(safePluginId)) {
            return Optional.of(platformLogStoreFile(
                    plugin.getConfig().getString("audit.yaml-folder", "logs/plugins/ledat-platform"),
                    plugin.getConfig().getString("audit.yaml-file", "platform_audit"),
                    "platform_audit"));
        }
        if ("platform_ledger".equals(safePluginId)) {
            return Optional.of(platformLogStoreFile(
                    plugin.getConfig().getString("ledger.yaml-folder", "logs/plugins/ledat-platform"),
                    plugin.getConfig().getString("ledger.yaml-file", "platform_ledger"),
                    "platform_ledger"));
        }
        return Optional.empty();
    }

    private File platformLogStoreFile(String folder, String name, String fallbackName) {
        String safeName = normalizeStoreName(name, fallbackName);
        File directory = resolveServerRootPath(folder == null || folder.isBlank() ? "logs/plugins/ledat-platform" : folder);
        File target = new File(directory, safeName + ".yml");
        migrateLegacyPlatformStore(safeName, target);
        return target;
    }

    private File resolveServerRootPath(String rawPath) {
        File path = new File(rawPath);
        if (path.isAbsolute()) {
            return path;
        }
        File serverRoot = plugin.getDataFolder().getParentFile();
        if (serverRoot != null && serverRoot.getParentFile() != null) {
            serverRoot = serverRoot.getParentFile();
        }
        if (serverRoot == null) {
            serverRoot = new File("").getAbsoluteFile();
        }
        return new File(serverRoot, rawPath);
    }

    private void migrateLegacyPlatformStore(String safeName, File target) {
        if (target.exists()) {
            return;
        }
        File legacy = new File(storageFolder(plugin), safeName + ".yml");
        if (!legacy.isFile()) {
            return;
        }
        try {
            ensureParent(target);
            Files.copy(legacy.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            logger.info("Migrated " + safeName + ".yml to root plugin logs: " + target.getPath());
        } catch (Exception error) {
            logger.warning("Could not migrate " + safeName + ".yml to " + target.getPath() + ": " + safeError(error));
        }
    }

    private String normalizeStoreName(String raw, String fallback) {
        String value = raw == null || raw.isBlank() ? fallback : raw;
        value = value.replace(".yml", "").replace(".yaml", "").replace(".sqlite", "").replace(".db", "");
        return sanitizeFileSegment(value);
    }

    private String sanitizeIdentifier(String raw) {
        String value = raw == null ? "ledat" : raw.toLowerCase(java.util.Locale.ROOT);
        value = IDENTIFIER_UNSAFE_PATTERN.matcher(value).replaceAll("_");
        value = REPEATED_UNDERSCORE_PATTERN.matcher(value).replaceAll("_");
        value = IDENTIFIER_EDGE_UNDERSCORE_PATTERN.matcher(value).replaceAll("");
        return value.isBlank() ? "ledat" : value;
    }

    private String sanitizeFileSegment(String raw) {
        String value = raw == null ? "data" : raw.toLowerCase(java.util.Locale.ROOT);
        value = FILE_SEGMENT_UNSAFE_PATTERN.matcher(value).replaceAll("_");
        value = value.replace("..", ".");
        value = REPEATED_UNDERSCORE_PATTERN.matcher(value).replaceAll("_");
        value = FILE_SEGMENT_EDGE_PATTERN.matcher(value).replaceAll("");
        return value.isBlank() ? "data" : value;
    }

    private void ensureParent(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            logger.warning("Could not create storage directory: " + parent.getPath());
        }
    }

    private String storageRootPath() {
        File root = plugin.getDataFolder().getParentFile();
        return root == null ? plugin.getDataFolder().getPath() : root.getPath();
    }

    private Exception checked(Throwable error) {
        if (error instanceof Exception exception) {
            return exception;
        }
        return new RuntimeException(error);
    }

    private RuntimeException unchecked(Throwable error) {
        if (error instanceof RuntimeException runtime) {
            return runtime;
        }
        return new RuntimeException(error);
    }

    private String safeError(Throwable error) {
        String message = error == null ? "unknown" : error.getMessage();
        return message == null || message.isBlank()
                ? (error == null ? "unknown" : error.getClass().getSimpleName())
                : SECRET_PATTERN.matcher(message).replaceAll("$1=***");
    }

    private Executor executorOrDirect() {
        ExecutorService target = executor;
        if (target == null || target.isShutdown()) {
            return DIRECT_EXECUTOR;
        }
        return target;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private final class ManagedSqliteStore implements SqliteStore {
        private final Plugin owner;
        private final String pluginId;
        private final String name;
        private final File file;
        private final AtomicLong storeSubmitted = new AtomicLong();
        private final AtomicLong storeCompleted = new AtomicLong();
        private final AtomicLong storeFailed = new AtomicLong();
        private volatile HikariDataSource source;
        private volatile boolean storeHealthy;
        private volatile String storeStatus = "not-started";
        private volatile String storeLastError = "";

        private ManagedSqliteStore(Plugin owner, String name) {
            this.owner = owner;
            this.pluginId = sanitizeIdentifier(owner.getName());
            this.name = normalizeStoreName(name, plugin.getConfig().getString("storage.standard-layout.default-sqlite-file", "data"));
            this.file = sqliteFile(owner, this.name);
            open();
        }

        private synchronized void open() {
            if (source != null) {
                return;
            }
            try {
                ensureParent(file);
                HikariConfig config = baseHikariConfig("LeDat-" + owner.getName() + "-" + name, true);
                config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
                config.setDriverClassName("org.sqlite.JDBC");
                config.setConnectionTestQuery("SELECT 1");
                source = new HikariDataSource(config);
                healthCheckDirect("open");
            } catch (Throwable error) {
                close();
                storeHealthy = false;
                storeStatus = "disabled: " + safeError(error);
                storeLastError = safeError(error);
                logger.log(Level.WARNING, "Local SQLite store could not start: " + pluginId + "/" + name, error);
            }
        }

        private synchronized void reopen() {
            if (owner != null && owner.isEnabled()) {
                open();
            }
        }

        @Override public String pluginId() { return pluginId; }
        @Override public String name() { return name; }
        @Override public File file() { return file; }
        @Override public boolean healthy() { return storeHealthy; }
        @Override public String status() { return storeStatus; }
        @Override public String lastError() { return storeLastError == null ? "" : storeLastError; }

        @Override
        public Connection borrowConnection(String requester) throws SQLException {
            if (source == null) {
                throw new SQLException("Local SQLite store is closed: " + pluginId + "/" + name);
            }
            guardMainThreadBorrow(requester);
            Connection connection = source.getConnection();
            applySqlitePragmas(connection, true);
            return connection;
        }

        @Override
        public <T> CompletableFuture<T> query(String requester, SqlFunction<T> function) {
            storeSubmitted.incrementAndGet();
            return DefaultDatabaseProvider.this.submitOwned(pluginId, requester, () -> {
                try (Connection connection = borrowConnection(requester)) {
                    T result = function.apply(connection);
                    storeCompleted.incrementAndGet();
                    return result;
                } catch (Throwable error) {
                    storeFailed.incrementAndGet();
                    storeLastError = safeError(error);
                    throw checked(error);
                }
            });
        }

        @Override
        public CompletableFuture<Void> execute(String requester, SqlConsumer consumer) {
            return query(requester, connection -> {
                consumer.accept(connection);
                return null;
            });
        }

        @Override
        public CompletableFuture<Void> transaction(String requester, SqlConsumer consumer) {
            storeSubmitted.incrementAndGet();
            return DefaultDatabaseProvider.this.submitOwned(pluginId, requester, () -> {
                try (Connection connection = borrowConnection(requester)) {
                    boolean old = connection.getAutoCommit();
                    connection.setAutoCommit(false);
                    try {
                        consumer.accept(connection);
                        connection.commit();
                        storeCompleted.incrementAndGet();
                        return null;
                    } catch (Throwable error) {
                        connection.rollback();
                        storeFailed.incrementAndGet();
                        storeLastError = safeError(error);
                        throw checked(error);
                    } finally {
                        connection.setAutoCommit(old);
                    }
                }
            });
        }

        @Override
        public void healthCheck() {
            healthCheckDirect("health");
        }

        private void healthCheckDirect(String reason) {
            HikariDataSource current = source;
            if (current == null) {
                storeHealthy = false;
                storeStatus = "closed";
                return;
            }
            if (skipBusyHealthCheck(current)) {
                storeStatus = "health skipped: pool busy @ " + Instant.now() + " (" + reason + ")";
                return;
            }
            try (Connection connection = current.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
                applySqlitePragmas(connection, true);
                statement.execute();
                if (plugin.getConfig().getBoolean("storage.local-sqlite.integrity.quick-check-on-health", false)) {
                    runQuickCheck(connection);
                }
                if (plugin.getConfig().getBoolean("storage.local-sqlite.checkpoint-on-health-check", false)) {
                    checkpointDirect(connection, "PASSIVE");
                }
                if (plugin.getConfig().getBoolean("storage.local-sqlite.optimize-on-health-check", false)) {
                    runPragma(connection, "optimize");
                }
                storeHealthy = true;
                storeStatus = "healthy @ " + Instant.now() + " (" + reason + ")";
                storeLastError = "";
            } catch (Throwable error) {
                storeHealthy = false;
                storeLastError = safeError(error);
                storeStatus = "unhealthy: " + safeError(error);
            }
        }

        private boolean skipBusyHealthCheck(HikariDataSource current) {
            if (!plugin.getConfig().getBoolean("storage.local-sqlite.health.skip-when-pool-busy", true)) {
                return false;
            }
            try {
                var bean = current.getHikariPoolMXBean();
                if (bean == null) {
                    return false;
                }
                int maxPool = current.getMaximumPoolSize();
                return maxPool > 0
                        && bean.getIdleConnections() <= 0
                        && bean.getTotalConnections() >= maxPool
                        && bean.getActiveConnections() >= maxPool;
            } catch (Throwable ignored) {
                return false;
            }
        }


        private CompletableFuture<Void> checkpoint(String reason) {
            return DefaultDatabaseProvider.this.submitOwned(pluginId, "sqlite-checkpoint:" + pluginId + ":" + name, () -> {
                HikariDataSource current = source;
                if (current == null) {
                    return null;
                }
                try (Connection connection = current.getConnection()) {
                    applySqlitePragmas(connection, true);
                    if (plugin.getConfig().getBoolean("storage.local-sqlite.optimize-on-checkpoint", true)) {
                        runPragma(connection, "optimize");
                    }
                    String mode = reason != null && reason.contains("close")
                            ? plugin.getConfig().getString("storage.local-sqlite.close-checkpoint-mode", "TRUNCATE")
                            : plugin.getConfig().getString("storage.local-sqlite.checkpoint-mode", "PASSIVE");
                    checkpointDirect(connection, normalizeCheckpointMode(mode));
                    storeStatus = "checkpointed @ " + Instant.now() + " (" + reason + ")";
                    storeLastError = "";
                } catch (Throwable error) {
                    sqliteCheckpointFailures.incrementAndGet();
                    storeFailed.incrementAndGet();
                    storeLastError = safeError(error);
                    storeStatus = "checkpoint failed: " + safeError(error);
                    throw checked(error);
                }
                return null;
            });
        }

        private void checkpointDirect(Connection connection, String mode) throws SQLException {
            String safeMode = normalizeCheckpointMode(mode);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(" + safeMode + ")");
                sqliteCheckpoints.incrementAndGet();
            }
        }

        private String normalizeCheckpointMode(String raw) {
            String value = raw == null ? "PASSIVE" : raw.trim().toUpperCase(java.util.Locale.ROOT);
            return switch (value) {
                case "PASSIVE", "FULL", "RESTART", "TRUNCATE" -> value;
                default -> "PASSIVE";
            };
        }

        private void runQuickCheck(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
                while (result.next()) {
                    String row = result.getString(1);
                    if (row != null && !row.equalsIgnoreCase("ok")) {
                        sqliteQuickCheckFailures.incrementAndGet();
                        throw new SQLException("SQLite quick_check failed for " + pluginId + "/" + name + ": " + row);
                    }
                }
            }
        }

        private void runPragma(Connection connection, String pragma) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA " + pragma);
            } catch (SQLException error) {
                if (plugin.getConfig().getBoolean("debug.enabled", false)) {
                    logger.fine("SQLite PRAGMA " + pragma + " failed for " + pluginId + "/" + name + ": " + safeError(error));
                }
            }
        }

        private String verifyDirect(boolean deep, boolean checkpoint) throws SQLException {
            HikariDataSource current = source;
            if (current == null) {
                throw new SQLException("closed");
            }
            try (Connection connection = current.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
                applySqlitePragmas(connection, true);
                statement.execute();
                DefaultDatabaseProvider.this.runSqliteIntegrityCheck(connection, deep, pluginId + "/" + name);
                if (checkpoint) {
                    checkpointDirect(connection, plugin.getConfig().getString("storage.local-sqlite.checkpoint-mode", "PASSIVE"));
                }
                if (plugin.getConfig().getBoolean("storage.local-sqlite.optimize-on-health-check", false)) {
                    runPragma(connection, "optimize");
                }
                storeHealthy = true;
                storeStatus = "verified @ " + Instant.now() + (deep ? " (integrity_check)" : " (quick_check)");
                storeLastError = "";
                return deep ? "integrity_check=ok" : "quick_check=ok";
            } catch (SQLException error) {
                storeHealthy = false;
                storeLastError = safeError(error);
                storeStatus = "verify failed: " + safeError(error);
                throw error;
            }
        }

        @Override
        public synchronized void close() {
            if (source != null) {
                try {
                    source.close();
                } catch (Throwable ignored) {
                }
                source = null;
            }
            storeHealthy = false;
            storeStatus = "closed";
        }

        private StorageUnit unit(String key) {
            return new StorageUnit(key, pluginId, name, "sqlite", file.getPath(), source != null, storeHealthy, false, storeSubmitted.get(), storeCompleted.get(), storeFailed.get(), storeStatus, storeLastError);
        }
    }

    private final class SimpleYamlStore implements YamlStore {
        private final Plugin owner;
        private final String pluginId;
        private final String name;
        private final File file;
        private final Object lock = new Object();
        private final Object fileWriteLock = new Object();
        private final YamlConfiguration yaml;
        private volatile Executor executor;
        private volatile boolean writeThrough;
        private final Object saveStateLock = new Object();
        private final AtomicBoolean dirty = new AtomicBoolean(false);
        private final AtomicLong version = new AtomicLong();
        private final AtomicLong storeSubmitted = new AtomicLong();
        private final AtomicLong storeCompleted = new AtomicLong();
        private final AtomicLong storeFailed = new AtomicLong();
        private CompletableFuture<Void> saveInFlight = CompletableFuture.completedFuture(null);
        private volatile String storeStatus = "loaded";
        private volatile String storeLastError = "";

        private SimpleYamlStore(Plugin owner, String pluginId, String name, Executor executor, boolean writeThrough) {
            this(owner, pluginId, name, null, executor, writeThrough);
        }

        private SimpleYamlStore(Plugin owner, String pluginId, String name, File fileOverride, Executor executor, boolean writeThrough) {
            this.owner = owner;
            this.pluginId = sanitizeIdentifier(pluginId == null || pluginId.isBlank() ? owner.getName() : pluginId);
            this.name = normalizeStoreName(name, plugin.getConfig().getString("storage.standard-layout.default-yaml-file", "data"));
            this.file = fileOverride == null ? new File(storageFolder(owner), this.name + ".yml") : fileOverride;
            ensureParent(file);
            this.yaml = YamlConfiguration.loadConfiguration(file);
            this.executor = executor;
            this.writeThrough = writeThrough;
        }

        private void rebind(Executor executor, boolean writeThrough) {
            this.executor = executor == null ? DIRECT_EXECUTOR : executor;
            this.writeThrough = writeThrough;
        }

        @Override public String pluginId() { return pluginId; }
        @Override public String name() { return name; }
        @Override public File file() { return file; }
        @Override public boolean dirty() { return dirty.get(); }

        @Override
        public CompletableFuture<Void> set(String key, Object value) {
            if (!writeThrough) {
                return runInline(() -> {
                    synchronized (lock) {
                        yaml.set(key, value);
                        markDirty();
                    }
                });
            }
            return run(() -> {
                synchronized (lock) {
                    yaml.set(key, value);
                    markDirty();
                }
                if (writeThrough) {
                    saveNow();
                }
            });
        }

        @Override
        public CompletableFuture<Void> remove(String key) {
            return set(key, null);
        }

        @Override
        public CompletableFuture<Optional<Object>> get(String key) {
            if (!writeThrough) {
                return supplyInline(() -> {
                    synchronized (lock) {
                        return Optional.ofNullable(yaml.get(key));
                    }
                });
            }
            return supply(() -> {
                synchronized (lock) {
                    return Optional.ofNullable(yaml.get(key));
                }
            });
        }

        @Override
        public CompletableFuture<Map<String, Object>> snapshot() {
            if (!writeThrough) {
                return supplyInline(() -> {
                    synchronized (lock) {
                        return new LinkedHashMap<>(yaml.getValues(true));
                    }
                });
            }
            return supply(() -> {
                synchronized (lock) {
                    return new LinkedHashMap<>(yaml.getValues(true));
                }
            });
        }

        @Override
        public CompletableFuture<YamlConfiguration> configurationSnapshot() {
            if (!writeThrough) {
                return supplyInline(this::copyConfiguration);
            }
            return supply(() -> {
                return copyConfiguration();
            });
        }

        @Override
        public CompletableFuture<Void> replace(YamlConfiguration configuration) {
            if (!writeThrough) {
                return runInline(() -> replaceInMemory(configuration));
            }
            return run(() -> {
                replaceInMemory(configuration);
                saveNow();
            });
        }

        @Override
        public CompletableFuture<Void> save() {
            return saveDirtyOnly();
        }

        private CompletableFuture<Void> saveDirtyOnly() {
            if (!dirty.get()) {
                return CompletableFuture.completedFuture(null);
            }
            synchronized (saveStateLock) {
                if (!dirty.get()) {
                    return CompletableFuture.completedFuture(null);
                }
                if (!saveInFlight.isDone()) {
                    return saveInFlight.thenCompose(ignored -> dirty.get()
                            ? saveDirtyOnly()
                            : CompletableFuture.completedFuture(null));
                }
                CompletableFuture<Void> current = run(() -> {
                    if (dirty.get()) {
                        saveNow();
                    }
                });
                saveInFlight = current;
                current.whenComplete((ignored, error) -> {
                    synchronized (saveStateLock) {
                        if (saveInFlight == current) {
                            saveInFlight = CompletableFuture.completedFuture(null);
                        }
                    }
                });
                return current.thenCompose(ignored -> dirty.get()
                        ? saveDirtyOnly()
                        : CompletableFuture.completedFuture(null));
            }
        }

        private void saveNow() {
            YamlSaveSnapshot snapshot = snapshotForSave();
            if (snapshot == null) {
                return;
            }
            try {
                ensureParent(file);
                String serialized = snapshot.configuration().saveToString();
                synchronized (fileWriteLock) {
                    synchronized (lock) {
                        if (version.get() != snapshot.version()) {
                            storeStatus = "save superseded @ " + Instant.now();
                            return;
                        }
                    }
                    Path target = file.toPath();
                    Path tmp = target.resolveSibling(file.getName() + ".tmp");
                    Files.writeString(tmp, serialized, StandardCharsets.UTF_8);
                    if (backupBeforeReplace() && Files.exists(target)) {
                        Path backup = target.resolveSibling(file.getName() + ".bak");
                        try {
                            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                        } catch (Throwable backupError) {
                            if (plugin.getConfig().getBoolean("debug.enabled", false)) {
                                logger.fine("Could not write YAML backup for " + file.getPath() + ": " + safeError(backupError));
                            }
                        }
                    }
                    try {
                        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (Throwable atomicMoveUnsupported) {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    yamlAtomicWrites.incrementAndGet();
                    synchronized (lock) {
                        if (version.get() == snapshot.version()) {
                            dirty.set(false);
                        }
                    }
                }
                storeStatus = "saved atomically @ " + Instant.now();
                storeLastError = "";
            } catch (Exception error) {
                yamlAtomicWriteFailures.incrementAndGet();
                storeFailed.incrementAndGet();
                dirty.set(true);
                storeLastError = safeError(error);
                storeStatus = "save failed: " + safeError(error);
                throw new RuntimeException(error);
            }
        }

        private YamlSaveSnapshot snapshotForSave() {
            synchronized (lock) {
                if (!dirty.get()) {
                    return null;
                }
                return new YamlSaveSnapshot(version.get(), copyConfigurationUnsafe());
            }
        }

        private void markDirty() {
            version.incrementAndGet();
            dirty.set(true);
        }

        private boolean backupBeforeReplace() {
            boolean enabled = plugin.getConfig().getBoolean("storage.yaml.backup-before-replace", false);
            if (owner != null && owner != plugin && owner.getConfig().contains("storage.yaml.backup-before-replace")) {
                enabled = owner.getConfig().getBoolean("storage.yaml.backup-before-replace", enabled);
            }
            return enabled;
        }

        private void replaceInMemory(YamlConfiguration configuration) {
            synchronized (lock) {
                for (String key : new java.util.ArrayList<>(yaml.getKeys(false))) {
                    yaml.set(key, null);
                }
                if (configuration != null) {
                    for (String key : configuration.getKeys(true)) {
                        if (!configuration.isConfigurationSection(key)) {
                            yaml.set(key, configuration.get(key));
                        }
                    }
                }
                markDirty();
            }
        }

        private YamlConfiguration copyConfiguration() {
            synchronized (lock) {
                return copyConfigurationUnsafe();
            }
        }

        private YamlConfiguration copyConfigurationUnsafe() {
            YamlConfiguration copy = new YamlConfiguration();
            for (String key : yaml.getKeys(true)) {
                if (!yaml.isConfigurationSection(key)) {
                    copy.set(key, yaml.get(key));
                }
            }
            return copy;
        }

        private CompletableFuture<Void> runInline(Runnable runnable) {
            storeSubmitted.incrementAndGet();
            try {
                runnable.run();
                storeCompleted.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            } catch (Throwable error) {
                storeFailed.incrementAndGet();
                storeLastError = safeError(error);
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(unchecked(error));
                return failed;
            }
        }

        private <T> CompletableFuture<T> supplyInline(java.util.function.Supplier<T> supplier) {
            storeSubmitted.incrementAndGet();
            try {
                T value = supplier.get();
                storeCompleted.incrementAndGet();
                return CompletableFuture.completedFuture(value);
            } catch (Throwable error) {
                storeFailed.incrementAndGet();
                storeLastError = safeError(error);
                CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(unchecked(error));
                return failed;
            }
        }

        private CompletableFuture<Void> run(Runnable runnable) {
            storeSubmitted.incrementAndGet();
            // folia-audit: ignore async-bukkit-risk -- storage unit YAML/SQL file I/O only; callers reschedule before Bukkit API.
            return CompletableFuture.runAsync(() -> {
                try {
                    runnable.run();
                    storeCompleted.incrementAndGet();
                } catch (Throwable error) {
                    storeFailed.incrementAndGet();
                    storeLastError = safeError(error);
                    throw unchecked(error);
                }
            }, executor);
        }

        private <T> CompletableFuture<T> supply(java.util.function.Supplier<T> supplier) {
            storeSubmitted.incrementAndGet();
            // folia-audit: ignore async-bukkit-risk -- storage unit YAML/SQL file I/O only; callers reschedule before Bukkit API.
            return CompletableFuture.supplyAsync(() -> {
                try {
                    T value = supplier.get();
                    storeCompleted.incrementAndGet();
                    return value;
                } catch (Throwable error) {
                    storeFailed.incrementAndGet();
                    storeLastError = safeError(error);
                    throw unchecked(error);
                }
            }, executor);
        }

        private StorageUnit unit(String key) {
            return new StorageUnit(key, pluginId, name, "yaml", file.getPath(), true, true, dirty.get(), storeSubmitted.get(), storeCompleted.get(), storeFailed.get(), storeStatus, storeLastError);
        }

        private record YamlSaveSnapshot(long version, YamlConfiguration configuration) {}
    }
}
