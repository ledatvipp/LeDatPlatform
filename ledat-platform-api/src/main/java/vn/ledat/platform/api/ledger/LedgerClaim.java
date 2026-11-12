package vn.ledat.platform.api.ledger;

public record LedgerClaim(
        String transactionId,
        String idempotencyKey,
        boolean claimed,
        String existingStatus
) {
    public static LedgerClaim claimed(String transactionId, String idempotencyKey) {
        return new LedgerClaim(transactionId, idempotencyKey, true, "new");
    }

    public static LedgerClaim duplicate(String transactionId, String idempotencyKey, String existingStatus) {
        return new LedgerClaim(transactionId, idempotencyKey, false, existingStatus == null ? "unknown" : existingStatus);
    }

    public boolean duplicate() {
        return !claimed;
    }
}
