package vn.ledat.platform.core.database;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultDatabaseProviderMysqlContainerTest {

    @TempDir
    Path tempDir;

    @Test
    void transactionRollbackReconnectReloadAndDuplicateClaimWorkAgainstMysql() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the MySQL Testcontainers integration test");

        try (MySQLContainer mysql = new MySQLContainer("mysql:8.4.0")) {
            mysql.start();
            Plugin plugin = plugin(tempDir.resolve("mysql"), mysql);
            DefaultDatabaseProvider provider = new DefaultDatabaseProvider(plugin);
            try {
                assertTrue(provider.healthy(), provider.status());
                provider.execute("mysql-schema", connection -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute("CREATE TABLE IF NOT EXISTS rollback_probe (id INT PRIMARY KEY, value VARCHAR(64) NOT NULL)");
                        statement.execute("CREATE TABLE IF NOT EXISTS claim_probe (idempotency_key VARCHAR(128) PRIMARY KEY)");
                    }
                }).get(10, TimeUnit.SECONDS);

                assertThrows(CompletionException.class, () -> provider.transaction("mysql-rollback", connection -> {
                    try (var statement = connection.prepareStatement("INSERT INTO rollback_probe (id, value) VALUES (?, ?)")) {
                        statement.setInt(1, 1);
                        statement.setString(2, "must-rollback");
                        statement.executeUpdate();
                    }
                    throw new SQLException("force rollback");
                }).join());
                assertEquals(0L, count(provider, "rollback_probe"));

                provider.reload();
                assertTrue(provider.healthy(), "provider must reconnect during reload: " + provider.status());
                assertEquals(0L, count(provider, "rollback_probe"));

                var first = provider.query("mysql-claim-1", connection -> insertClaim(connection, "duplicate-key"));
                var second = provider.query("mysql-claim-2", connection -> insertClaim(connection, "duplicate-key"));
                int claimed = (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
                assertEquals(1, claimed, "MySQL unique key must allow exactly one duplicate claim");
            } finally {
                provider.close();
            }
        }
    }

    private static boolean insertClaim(java.sql.Connection connection, String key) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT IGNORE INTO claim_probe (idempotency_key) VALUES (?)")) {
            statement.setString(1, key);
            return statement.executeUpdate() == 1;
        }
    }

    private static long count(DefaultDatabaseProvider provider, String table) throws Exception {
        return provider.query("mysql-count", connection -> {
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                result.next();
                return result.getLong(1);
            }
        }).get(10, TimeUnit.SECONDS);
    }

    private static Plugin plugin(Path dataFolder, MySQLContainer mysql) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.mode", "MYSQL");
        config.set("storage.mysql.host", mysql.getHost());
        config.set("storage.mysql.port", mysql.getFirstMappedPort());
        config.set("storage.mysql.database", mysql.getDatabaseName());
        config.set("storage.mysql.username", mysql.getUsername());
        config.set("storage.mysql.password", mysql.getPassword());
        config.set("storage.mysql.ssl", false);
        config.set("storage.pool.maximum-pool-size", 4);
        config.set("storage.pool.minimum-idle", 1);
        config.set("storage.pool.initialization-fail-timeout-ms", 10_000L);
        config.set("storage.executor.core-threads", 2);
        config.set("storage.executor.max-threads", 4);
        config.set("storage.executor.prestart-core-threads", false);
        config.set("storage.executor.shutdown-timeout-ms", 10_000L);
        config.set("storage.safety.log-main-thread-borrows", false);
        Logger logger = Logger.getLogger("DefaultDatabaseProviderMysqlContainerTest");
        return (Plugin) Proxy.newProxyInstance(
                DefaultDatabaseProviderMysqlContainerTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConfig" -> config;
                    case "reloadConfig" -> null;
                    case "getLogger" -> logger;
                    case "getName" -> "LeDatPlatformMysqlTest";
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
