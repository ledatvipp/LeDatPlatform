package vn.ledat.platform.api.ledger;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record LedgerTransaction(
        String transactionId,
        String idempotencyKey,
        String pluginId,
        String action,
        UUID actor,
        UUID target,
        String currency,
        BigDecimal amount,
        BigDecimal before,
        BigDecimal after,
        String reason,
        Map<String, String> metadata
) {
    public LedgerTransaction {
        transactionId = text(transactionId, UUID.randomUUID().toString());
        idempotencyKey = text(idempotencyKey, transactionId);
        pluginId = text(pluginId, "unknown");
        action = text(action, "unknown");
        currency = text(currency, "unknown");
        amount = amount == null ? BigDecimal.ZERO : amount;
        reason = text(reason, "");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
