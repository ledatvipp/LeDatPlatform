package vn.ledat.platform.api.economy;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Provider-neutral economy registry. LeDatPlatform ships guarded runtime bridges
 * for Vault and PlayerPoints; they register only when the corresponding server
 * plugin is present and available.
 */
public interface EconomyService {
    void register(Plugin owner, EconomyAdapter adapter);
    void unregister(Plugin owner);
    Optional<EconomyAdapter> provider(String id);
    Optional<EconomyAdapter> primary();
    Map<String, EconomyAdapter> providers();

    static EconomyService disabled() {
        return new EconomyService() {
            @Override public void register(Plugin owner, EconomyAdapter adapter) { }
            @Override public void unregister(Plugin owner) { }
            @Override public Optional<EconomyAdapter> provider(String id) { return Optional.empty(); }
            @Override public Optional<EconomyAdapter> primary() { return Optional.empty(); }
            @Override public Map<String, EconomyAdapter> providers() { return Map.of(); }
        };
    }

    interface EconomyAdapter {
        String id();
        String displayName();
        boolean available();
        BigDecimal balance(OfflinePlayer player);
        boolean has(OfflinePlayer player, BigDecimal amount);
        TransactionResult withdraw(OfflinePlayer player, BigDecimal amount);
        TransactionResult deposit(OfflinePlayer player, BigDecimal amount);
    }

    record TransactionResult(boolean success, BigDecimal amount, BigDecimal balance, String error) {
        public static TransactionResult failed(BigDecimal amount, String error) {
            return new TransactionResult(false, amount == null ? BigDecimal.ZERO : amount, BigDecimal.ZERO, error == null ? "unknown" : error);
        }
    }
}
