package vn.ledat.platform.api.duel;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Optional wager lifecycle consumed by LeDatDuels without a hard dependency on
 * any concrete currency or progression plugin.
 */
public interface DuelWagerProvider {
    CompletableFuture<ReserveResult> reserve(ReserveRequest request);

    CompletableFuture<SettlementResult> settle(SettlementRequest request);

    record ReserveRequest(UUID matchId, UUID playerOne, UUID playerTwo, int stakePerPlayer,
                          String idempotencyKey) {
    }

    record ReserveResult(boolean accepted, String reason) {
        public static ReserveResult approved() {
            return new ReserveResult(true, "accepted");
        }

        public static ReserveResult rejected(String reason) {
            return new ReserveResult(false, reason == null ? "rejected" : reason);
        }
    }

    record SettlementRequest(UUID matchId, UUID playerOne, UUID playerTwo, UUID winner,
                             Outcome outcome, int stakePerPlayer, String idempotencyKey) {
    }

    record SettlementResult(boolean settled, boolean duplicate, String reason) {
        public static SettlementResult success(boolean duplicate) {
            return new SettlementResult(true, duplicate, duplicate ? "duplicate" : "settled");
        }

        public static SettlementResult failed(String reason) {
            return new SettlementResult(false, false, reason == null ? "failed" : reason);
        }
    }

    enum Outcome {
        WIN,
        DRAW,
        FORFEIT,
        CANCELLED
    }
}
