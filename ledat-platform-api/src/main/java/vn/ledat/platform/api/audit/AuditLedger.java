package vn.ledat.platform.api.audit;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.List;

public interface AuditLedger {
    CompletableFuture<Void> record(AuditRecord record);
    /**
     * Persists an audit record and verifies that the configured sink can read it back.
     * Implementations that cannot provide this guarantee fail closed.
     */
    default CompletableFuture<Boolean> recordVerified(AuditRecord record) {
        return CompletableFuture.completedFuture(false);
    }
    default CompletableFuture<Void> recordAll(List<AuditRecord> records) {
        if (records == null || records.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.allOf(records.stream().map(this::record).toArray(CompletableFuture[]::new));
    }
    CompletableFuture<Void> economy(String pluginId, String action, UUID actor, UUID target, String currency, BigDecimal amount, String result, Map<String, String> metadata);
    CompletableFuture<Void> item(String pluginId, String action, UUID actor, String itemType, int amount, String result, Map<String, String> metadata);
    void flush();
    void reload();
}
