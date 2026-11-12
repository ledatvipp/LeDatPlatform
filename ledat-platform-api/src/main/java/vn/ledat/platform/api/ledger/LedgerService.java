package vn.ledat.platform.api.ledger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface LedgerService {
    CompletableFuture<LedgerClaim> claim(LedgerTransaction transaction);

    CompletableFuture<Void> complete(String pluginId, String transactionId, String idempotencyKey, String status, Map<String, String> metadata);

    default CompletableFuture<Void> success(String pluginId, String transactionId, String idempotencyKey, Map<String, String> metadata) {
        return complete(pluginId, transactionId, idempotencyKey, "success", metadata);
    }

    default CompletableFuture<Void> fail(String pluginId, String transactionId, String idempotencyKey, String reason, Map<String, String> metadata) {
        Map<String, String> safeMetadata = metadata == null ? Map.of("reason", reason == null ? "" : reason) : metadata;
        return complete(pluginId, transactionId, idempotencyKey, "failed", safeMetadata);
    }

    void flush();

    void reload();

    static LedgerService disabled() {
        return DisabledLedgerService.INSTANCE;
    }

    final class DisabledLedgerService implements LedgerService {
        private static final DisabledLedgerService INSTANCE = new DisabledLedgerService();

        private DisabledLedgerService() {
        }

        @Override
        public CompletableFuture<LedgerClaim> claim(LedgerTransaction transaction) {
            return CompletableFuture.completedFuture(LedgerClaim.claimed(transaction.transactionId(), transaction.idempotencyKey()));
        }

        @Override
        public CompletableFuture<Void> complete(String pluginId, String transactionId, String idempotencyKey, String status, Map<String, String> metadata) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void flush() {
        }

        @Override
        public void reload() {
        }
    }
}
