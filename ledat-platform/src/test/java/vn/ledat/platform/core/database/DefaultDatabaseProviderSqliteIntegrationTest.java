package vn.ledat.platform.core.database;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.ledat.platform.api.database.DatabaseProvider;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultDatabaseProviderSqliteIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void transactionRollbackReloadReconnectAndDuplicateClaimAreEnforced() throws Exception {
        Plugin plugin = plugin(tempDir.resolve("sqlite"));
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(plugin);

        try {
            provider.execute("sqlite-schema", connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE IF NOT EXISTS rollback_probe (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
                    statement.execute("CREATE TABLE IF NOT EXISTS claim_probe (idempotency_key TEXT PRIMARY KEY)");
                }
            }).get(5, TimeUnit.SECONDS);

            assertThrows(CompletionException.class, () -> provider.transaction("sqlite-rollback", connection -> {
                try (var statement = connection.prepareStatement("INSERT INTO rollback_probe (id, value) VALUES (?, ?)")) {
                    statement.setInt(1, 1);
                    statement.setString(2, "must-rollback");
                    statement.executeUpdate();
                }
                throw new SQLException("force rollback");
            }).join());
            assertEquals(0L, count(provider, "rollback_probe"));

            DatabaseProvider.SqliteStore retained = provider.sqliteStore(plugin, "retained").orElseThrow();
            retained.execute("local-schema", connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE IF NOT EXISTS retained_probe (id INTEGER PRIMARY KEY)");
                    statement.execute("INSERT OR IGNORE INTO retained_probe (id) VALUES (1)");
                }
            }).get(5, TimeUnit.SECONDS);

            provider.reload();
            assertSame(retained, provider.sqliteStore(plugin, "retained").orElseThrow(),
                    "reload must reopen the same public SQLite store handle");
            assertEquals(1L, retained.query("retained-count", connection -> {
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT COUNT(*) FROM retained_probe")) {
                    result.next();
                    return result.getLong(1);
                }
            }).get(5, TimeUnit.SECONDS));
            assertEquals(0L, count(provider, "rollback_probe"), "rollback must remain durable after reconnect");

            var first = provider.query("sqlite-claim-1", connection -> insertClaim(connection, "duplicate-key"));
            var second = provider.query("sqlite-claim-2", connection -> insertClaim(connection, "duplicate-key"));
            int claimed = (first.get(5, TimeUnit.SECONDS) ? 1 : 0) + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, claimed, "database unique key must allow exactly one duplicate claim");
        } finally {
            provider.close();
        }
    }

    @Test
    void deferredPluginCloseWaitsForThePersistenceBarrierThenReleasesTheStore() throws Exception {
        Plugin plugin = plugin(tempDir.resolve("deferred-close"));
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(plugin);
        try {
            DatabaseProvider.SqliteStore store = provider.sqliteStore(plugin, "deferred").orElseThrow();
            store.execute("deferred-schema", connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE IF NOT EXISTS deferred_probe (id INTEGER PRIMARY KEY)");
                    statement.execute("INSERT OR IGNORE INTO deferred_probe (id) VALUES (1)");
                }
            }).get(5, TimeUnit.SECONDS);

            var barrier = new java.util.concurrent.CompletableFuture<Void>();
            var close = provider.closePluginAfter(plugin.getName(), barrier);
            assertFalse(close.isDone());
            assertEquals(1L, provider.storageUnits().values().stream()
                    .filter(unit -> unit.pluginId().equals("ledatplatformsqlitetest")).count());

            barrier.complete(null);
            close.get(5, TimeUnit.SECONDS);
            assertTrue(provider.storageUnits().isEmpty());

            DatabaseProvider.SqliteStore reopened = provider.sqliteStore(plugin, "deferred").orElseThrow();
            assertNotSame(store, reopened);
            assertEquals(1L, reopened.query("deferred-count", connection -> {
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT COUNT(*) FROM deferred_probe")) {
                    result.next();
                    return result.getLong(1);
                }
            }).get(5, TimeUnit.SECONDS));
        } finally {
            provider.close();
        }
    }

    private static boolean insertClaim(java.sql.Connection connection, String key) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT OR IGNORE INTO claim_probe (idempotency_key) VALUES (?)")) {
            statement.setString(1, key);
            return statement.executeUpdate() == 1;
        }
    }

    private static long count(DefaultDatabaseProvider provider, String table) throws Exception {
        return provider.query("sqlite-count", connection -> {
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                result.next();
                return result.getLong(1);
            }
        }).get(5, TimeUnit.SECONDS);
    }

    private static Plugin plugin(Path dataFolder) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.mode", "SQLITE");
        config.set("storage.sqlite.file", "platform.db");
        config.set("storage.local-sqlite.enabled", true);
        config.set("storage.executor.core-threads", 2);
        config.set("storage.executor.max-threads", 4);
        config.set("storage.executor.prestart-core-threads", false);
        config.set("storage.executor.shutdown-timeout-ms", 5_000L);
        config.set("storage.safety.log-main-thread-borrows", false);
        Logger logger = Logger.getLogger("DefaultDatabaseProviderSqliteIntegrationTest");
        return (Plugin) Proxy.newProxyInstance(
                DefaultDatabaseProviderSqliteIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConfig" -> config;
                    case "reloadConfig" -> null;
                    case "getLogger" -> logger;
                    case "getName" -> "LeDatPlatformSqliteTest";
                    case "getDataFolder" -> dataFolder.toFile();
                    case "isEnabled" -> true;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == float.class || type == double.class) return 0.0D;
        return 0;
    }
}
