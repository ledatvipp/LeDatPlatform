package vn.ledat.platform.core.ledger;

import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.audit.AuditLedger;
import vn.ledat.platform.api.audit.AuditRecord;
import vn.ledat.platform.api.database.DatabaseProvider;
import vn.ledat.platform.api.ledger.LedgerClaim;
import vn.ledat.platform.api.ledger.LedgerService;
import vn.ledat.platform.api.ledger.LedgerTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DefaultLedgerService implements LedgerService {
    private static final String STORE_NAME = "platform_ledger";
    private static final String CLAIM_PREFIX = "claims.";
    private static final String STATUS_SUFFIX = ".status";

    private final Plugin plugin;
    private final DatabaseProvider database;
    private final AuditLedger audit;
    private final Logger logger;
    private final ConcurrentHashMap<String, String> statuses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LedgerTransaction> transactions = new ConcurrentHashMap<>();

    public DefaultLedgerService(Plugin plugin, DatabaseProvider database, AuditLedger audit, Logger logger) {
        this.plugin = plugin;
        this.database = database;
        this.audit = audit;
        this.logger = logger;
        reload();
    }

    @Override
    public CompletableFuture<LedgerClaim> claim(LedgerTransaction transaction) {
        if (transaction == null || !enabled()) {
            LedgerTransaction fallback = transaction == null
                    ? new LedgerTransaction(null, null, "unknown", "unknown", null, null, "unknown", BigDecimal.ZERO, null, null, "", Map.of())
                    : transaction;
            return CompletableFuture.completedFuture(LedgerClaim.claimed(fallback.transactionId(), fallback.idempotencyKey()));
        }

        String key = key(transaction.pluginId(), transaction.idempotencyKey());
        String existing = statuses.putIfAbsent(key, "pending");
        if (existing != null) {
            recordTransition(transaction, "duplicate", Map.of("existing_status", existing));
            return CompletableFuture.completedFuture(LedgerClaim.duplicate(transaction.transactionId(), transaction.idempotencyKey(), existing));
        }

        transactions.put(key, transaction);
        persist(key, transaction, "pending", Map.of("phase", "claim"));
        recordTransition(transaction, "pending", Map.of("phase", "claim"));
        return CompletableFuture.completedFuture(LedgerClaim.claimed(transaction.transactionId(), transaction.idempotencyKey()));
    }

    @Override
    public CompletableFuture<Void> complete(String pluginId, String transactionId, String idempotencyKey, String status, Map<String, String> metadata) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        String safeStatus = safeStatus(status);
        String key = key(pluginId, idempotencyKey);
        statuses.put(key, safeStatus);
        LedgerTransaction transaction = transactions.get(key);
        if (transaction == null) {
            transaction = new LedgerTransaction(transactionId, idempotencyKey, pluginId, "unknown", null, null, "unknown", BigDecimal.ZERO, null, null, "", metadata);
            transactions.put(key, transaction);
        }
        CompletableFuture<Void> persisted = persist(key, transaction, safeStatus, metadata);
        CompletableFuture<Void> audited = recordTransition(transaction, safeStatus, metadata);
        return CompletableFuture.allOf(persisted, audited);
    }

    @Override
    public void flush() {
        if (!enabled()) {
            return;
        }
        Optional<DatabaseProvider.YamlStore> store = store();
        if (store.isEmpty()) {
            return;
        }
        try {
            store.get().save().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logger.warning("Ledger YAML flush interrupted.");
        } catch (Exception error) {
            logger.warning("Ledger YAML flush failed: " + safe(error.getMessage()));
        }
    }

    @Override
    public void reload() {
        if (!enabled()) {
            return;
        }
        store().ifPresent(store -> store.snapshot().thenAccept(snapshot -> {
            for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
                String path = entry.getKey();
                if (!path.startsWith(CLAIM_PREFIX) || !path.endsWith(STATUS_SUFFIX)) {
                    continue;
                }
                String key = path.substring(CLAIM_PREFIX.length(), path.length() - STATUS_SUFFIX.length());
                statuses.put(key, safeStatus(String.valueOf(entry.getValue())));
            }
        }).exceptionally(error -> {
            logger.log(Level.WARNING, "Ledger snapshot load failed", error);
            return null;
        }));
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("ledger.enabled", false)
                && plugin.getConfig().getBoolean("ledger.write-enabled", false);
    }

    private Optional<DatabaseProvider.YamlStore> store() {
        try {
            return database == null ? Optional.empty() : database.yamlStore(STORE_NAME);
        } catch (RuntimeException error) {
            logger.log(Level.WARNING, "Ledger store unavailable", error);
            return Optional.empty();
        }
    }

    private CompletableFuture<Void> persist(String key, LedgerTransaction transaction, String status, Map<String, String> metadata) {
        Optional<DatabaseProvider.YamlStore> store = store();
        if (store.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("transaction-id", transaction.transactionId());
        row.put("idempotency-key", transaction.idempotencyKey());
        row.put("plugin", transaction.pluginId());
        row.put("action", transaction.action());
        row.put("actor", transaction.actor() == null ? "" : transaction.actor().toString());
        row.put("target", transaction.target() == null ? "" : transaction.target().toString());
        row.put("currency", transaction.currency());
        row.put("amount", transaction.amount().toPlainString());
        row.put("before", decimal(transaction.before()));
        row.put("after", decimal(transaction.after()));
        row.put("reason", transaction.reason());
        row.put("status", safeStatus(status));
        row.put("metadata", metadataCopy(metadata));
        row.put("updated-at", Instant.now().toString());
        return store.get().set(CLAIM_PREFIX + key, row).exceptionally(error -> {
            logger.log(Level.WARNING, "Ledger claim persist failed for " + transaction.pluginId() + "/" + transaction.action(), error);
            return null;
        });
    }

    private CompletableFuture<Void> recordTransition(LedgerTransaction transaction, String status, Map<String, String> extraMetadata) {
        if (audit == null) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, String> metadata = new LinkedHashMap<>(metadataCopy(transaction.metadata()));
        metadata.put("transaction_id", transaction.transactionId());
        metadata.put("idempotency_key", transaction.idempotencyKey());
        metadata.put("reason", transaction.reason());
        metadata.put("before", decimal(transaction.before()));
        metadata.put("after", decimal(transaction.after()));
        if (extraMetadata != null) {
            metadata.putAll(metadataCopy(extraMetadata));
        }
        return audit.record(new AuditRecord(
                transaction.pluginId(),
                "ledger",
                transaction.action(),
                transaction.actor(),
                transaction.target(),
                safeStatus(status),
                transaction.currency(),
                transaction.amount().toPlainString(),
                metadata,
                null
        )).exceptionally(error -> {
            logger.log(Level.WARNING, "Ledger audit transition failed for " + transaction.pluginId() + "/" + transaction.action(), error);
            return null;
        });
    }

    private String key(String pluginId, String idempotencyKey) {
        String raw = safe(pluginId) + ":" + safe(idempotencyKey);
        String clean = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_").replaceAll("_+", "_");
        if (clean.length() <= 128) {
            return clean;
        }
        String hash = Integer.toHexString(raw.hashCode());
        return clean.substring(0, 112) + "_" + hash;
    }

    private String safeStatus(String status) {
        String value = safe(status).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return value.isBlank() ? "unknown" : value;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String decimal(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private Map<String, String> metadataCopy(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            copy.put(safe(entry.getKey()), safe(entry.getValue()));
        }
        return copy;
    }
}
