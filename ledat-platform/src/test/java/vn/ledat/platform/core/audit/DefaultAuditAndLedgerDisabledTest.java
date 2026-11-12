package vn.ledat.platform.core.audit;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.ledat.platform.api.audit.AuditLedger;
import vn.ledat.platform.api.audit.AuditRecord;
import vn.ledat.platform.api.database.DatabaseProvider;
import vn.ledat.platform.api.database.StorageMode;
import vn.ledat.platform.api.ledger.LedgerClaim;
import vn.ledat.platform.api.ledger.LedgerTransaction;
import vn.ledat.platform.core.database.DefaultDatabaseProvider;
import vn.ledat.platform.core.ledger.DefaultLedgerService;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAuditAndLedgerDisabledTest {

    @TempDir
    Path tempDir;

    @Test
    void disabledAuditCompletesWithoutTouchingDatabase() throws Exception {
        Plugin plugin = plugin(tempDir.resolve("audit"), false, false, false, false);
        DefaultAuditLedger audit = new DefaultAuditLedger(plugin, databaseThatMustNotBeTouched(), plugin.getLogger());

        CompletableFuture<Void> recorded = audit.record(new AuditRecord(
                "ledat_test",
                "storage",
                "disabled_audit_probe",
                null,
                null,
                "ok",
                "text",
                "probe",
                Map.of("source", "test"),
                null
        ));

        assertTrue(recorded.isDone());
        recorded.get(1, TimeUnit.SECONDS);
        assertFalse(audit.recordVerified(probe()).get(1, TimeUnit.SECONDS));
        audit.flush();
        audit.reload();
    }

    @Test
    void verifiedYamlAuditRequiresDurableReadBack() throws Exception {
        Plugin plugin = plugin(tempDir.resolve("verified-yaml"), true, true, false, false);
        DatabaseProvider database = mock(DatabaseProvider.class);
        DatabaseProvider.YamlStore store = mock(DatabaseProvider.YamlStore.class);
        AtomicReference<String> storedKey = new AtomicReference<>();
        java.io.File auditFile = tempDir.resolve("verified-yaml.yml").toFile();

        when(database.mode()).thenReturn(StorageMode.YAML);
        when(database.yamlStore("platform_audit")).thenReturn(java.util.Optional.of(store));
        when(store.file()).thenReturn(auditFile);
        when(store.set(any(), any())).thenAnswer(invocation -> {
            storedKey.set(invocation.getArgument(0));
            YamlConfiguration snapshot = new YamlConfiguration();
            snapshot.set(storedKey.get(), invocation.getArgument(1));
            snapshot.save(auditFile);
            return CompletableFuture.completedFuture(null);
        });
        when(store.save()).thenReturn(CompletableFuture.completedFuture(null));

        DefaultAuditLedger audit = new DefaultAuditLedger(plugin, database, plugin.getLogger());

        assertTrue(audit.recordVerified(probe()).get(1, TimeUnit.SECONDS));
        verify(store).save();
    }

    @Test
    void verifiedYamlAuditFailsClosedWhenReadBackDoesNotMatch() throws Exception {
        Plugin plugin = plugin(tempDir.resolve("failed-readback"), true, true, false, false);
        DatabaseProvider database = mock(DatabaseProvider.class);
        DatabaseProvider.YamlStore store = mock(DatabaseProvider.YamlStore.class);
        java.io.File auditFile = tempDir.resolve("failed-readback.yml").toFile();

        when(database.mode()).thenReturn(StorageMode.YAML);
        when(database.yamlStore("platform_audit")).thenReturn(java.util.Optional.of(store));
        when(store.file()).thenReturn(auditFile);
        when(store.set(any(), any())).thenAnswer(invocation -> {
            YamlConfiguration partial = new YamlConfiguration();
            partial.set(invocation.getArgument(0) + ".action", "disabled_audit_probe");
            partial.save(auditFile);
            return CompletableFuture.completedFuture(null);
        });
        when(store.save()).thenReturn(CompletableFuture.completedFuture(null));

        DefaultAuditLedger audit = new DefaultAuditLedger(plugin, database, plugin.getLogger());

        assertFalse(audit.recordVerified(probe()).get(1, TimeUnit.SECONDS));
        verify(database, never()).query(any(), any());
    }

    @Test
    void verifiedSqlAuditMatchesActorTargetMetadataAndFailsClosedWithoutReadBack() throws Exception {
        Plugin plugin = plugin(tempDir.resolve("verified-sql"), true, true, false, false);
        DatabaseProvider database = mock(DatabaseProvider.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        AuditRecord record = new AuditRecord(
                "ledat_test", "player_info", "player_ip_pre_reveal", actor, target,
                "authorized", "none", "", Map.of(), java.time.Instant.now());

        when(database.mode()).thenReturn(StorageMode.SQLITE);
        when(database.enabled()).thenReturn(true);
        when(database.healthy()).thenReturn(true);
        when(database.tableName("platform", "audit_ledger")).thenReturn("ledat_platform_audit_ledger");
        when(database.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(connection.prepareStatement(any())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(database.query(any(), any())).thenAnswer(invocation -> {
            DatabaseProvider.SqlFunction<?> query = invocation.getArgument(1);
            return CompletableFuture.completedFuture(query.apply(connection));
        });

        DefaultAuditLedger audit = new DefaultAuditLedger(plugin, database, plugin.getLogger());
        assertTrue(audit.recordVerified(record).get(1, TimeUnit.SECONDS));
        verify(statement).setString(4, actor.toString());
        verify(statement).setString(6, target.toString());
        verify(statement).setString(11, "{}");
        verify(statement).setLong(12, record.timestamp().toEpochMilli());

        when(resultSet.next()).thenReturn(false);
        assertFalse(audit.recordVerified(record).get(1, TimeUnit.SECONDS));
    }

    @Test
    void verifiedYamlAuditPersistsAndReadsBackThroughRealStore() throws Exception {
        Plugin plugin = plugin(tempDir.resolve("real-yaml"), true, true, false, false);
        DefaultDatabaseProvider database = new DefaultDatabaseProvider(plugin);
        try {
            DefaultAuditLedger audit = new DefaultAuditLedger(plugin, database, plugin.getLogger());

            assertTrue(audit.recordVerified(probe()).get(5, TimeUnit.SECONDS));
            database.flushAll().get(5, TimeUnit.SECONDS);

            DatabaseProvider.YamlStore store = database.yamlStore("platform_audit").orElseThrow();
            assertTrue(store.file().isFile());
            assertTrue(YamlConfiguration.loadConfiguration(store.file()).getValues(true).values().stream()
                    .anyMatch(value -> "disabled_audit_probe".equals(String.valueOf(value))));
        } finally {
            database.close();
        }
    }

    @Test
    void disabledLedgerCompletesWithoutTouchingDatabaseOrAudit() throws Exception {
        Plugin plugin = plugin(tempDir.resolve("ledger"), false, false, false, false);
        DefaultLedgerService ledger = new DefaultLedgerService(
                plugin,
                databaseThatMustNotBeTouched(),
                auditThatMustNotBeTouched(),
                plugin.getLogger()
        );
        LedgerTransaction transaction = new LedgerTransaction(
                "tx-disabled",
                "idem-disabled",
                "ledat_test",
                "disabled_ledger_probe",
                null,
                null,
                "COIN",
                BigDecimal.ONE,
                null,
                null,
                "test",
                Map.of("source", "test")
        );

        LedgerClaim claim = ledger.claim(transaction).get(1, TimeUnit.SECONDS);
        assertTrue(claim.claimed());
        assertFalse(claim.duplicate());

        ledger.complete(transaction.pluginId(), transaction.transactionId(), transaction.idempotencyKey(), "success", Map.of())
                .get(1, TimeUnit.SECONDS);
        ledger.flush();
        ledger.reload();
    }

    private static DatabaseProvider databaseThatMustNotBeTouched() {
        return (DatabaseProvider) Proxy.newProxyInstance(
                DefaultAuditAndLedgerDisabledTest.class.getClassLoader(),
                new Class<?>[]{DatabaseProvider.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return defaultObjectMethod(proxy, method.getName());
                    }
                    throw new AssertionError("Disabled audit/ledger unexpectedly touched database." + method.getName());
                });
    }

    private static AuditRecord probe() {
        return new AuditRecord(
                "ledat_test",
                "storage",
                "disabled_audit_probe",
                null,
                null,
                "ok",
                "none",
                "",
                Map.of(),
                null
        );
    }

    private static AuditLedger auditThatMustNotBeTouched() {
        return (AuditLedger) Proxy.newProxyInstance(
                DefaultAuditAndLedgerDisabledTest.class.getClassLoader(),
                new Class<?>[]{AuditLedger.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return defaultObjectMethod(proxy, method.getName());
                    }
                    throw new AssertionError("Disabled ledger unexpectedly touched audit." + method.getName());
                });
    }

    private static Plugin plugin(Path dataFolder,
                                 boolean auditEnabled,
                                 boolean auditWriteEnabled,
                                 boolean ledgerEnabled,
                                 boolean ledgerWriteEnabled) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("audit.enabled", auditEnabled);
        config.set("audit.write-enabled", auditWriteEnabled);
        config.set("ledger.enabled", ledgerEnabled);
        config.set("ledger.write-enabled", ledgerWriteEnabled);
        config.set("storage.mode", "YAML");
        config.set("storage.yaml.write-through", false);
        config.set("storage.yaml.backup-before-replace", false);
        config.set("storage.standard-layout.default-yaml-file", "data");
        config.set("storage.executor.shutdown-timeout-ms", 1_000L);
        config.set("audit.yaml-folder", dataFolder.resolve("logs").toString());
        config.set("audit.yaml-file", "platform_audit");
        Logger logger = Logger.getLogger("DefaultAuditAndLedgerDisabledTest");
        return (Plugin) Proxy.newProxyInstance(
                DefaultAuditAndLedgerDisabledTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConfig" -> config;
                    case "reloadConfig" -> null;
                    case "getLogger" -> logger;
                    case "getName" -> "LeDatPlatformTest";
                    case "getDataFolder" -> dataFolder.toFile();
                    case "isEnabled" -> true;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultObjectMethod(Object proxy, String name) {
        return switch (name) {
            case "toString" -> "NoTouchProxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> false;
            default -> null;
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == short.class || type == byte.class || type == long.class) {
            return 0;
        }
        if (type == float.class || type == double.class) {
            return 0.0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
