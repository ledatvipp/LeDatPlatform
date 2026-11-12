package vn.ledat.platform.core.integration;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import vn.ledat.platform.api.LeDatPlatformApi;
import vn.ledat.platform.api.economy.EconomyService;
import vn.ledat.platform.api.item.CustomItemAdapter;
import vn.ledat.platform.api.item.CustomItemKey;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime integrations packaged with the core jar. Every bridge is guarded by
 * plugin presence/reflection, so a server never needs separate LeDat addon jars.
 */
public final class BuiltInIntegrations {
    private static Object placeholderExpansion;
    private static final ClassValue<Map<MethodSignature, Optional<Method>>> METHOD_CACHE = new ClassValue<>() {
        @Override
        protected Map<MethodSignature, Optional<Method>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };
    private static final ClassValue<Map<String, Optional<Field>>> FIELD_CACHE = new ClassValue<>() {
        @Override
        protected Map<String, Optional<Field>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private BuiltInIntegrations() {
    }

    public static void install(Plugin owner, LeDatPlatformApi api) {
        installItems(owner, api);
        installVault(owner, api);
        installPlayerPoints(owner, api);
        installLuckPerms(owner, api);
        installPlaceholderApi(owner, api);
        installFloodgate(owner, api);
    }

    public static void uninstall(Plugin owner, LeDatPlatformApi api) {
        if (placeholderExpansion != null) {
            try {
                placeholderExpansion.getClass().getMethod("unregister").invoke(placeholderExpansion);
            } catch (Throwable ignored) {
            }
        }
        placeholderExpansion = null;
        api.items().unregister(owner);
        api.economy().unregister(owner);
        for (String hook : List.of("CustomItems", "Vault", "PlayerPoints", "LuckPerms", "PlaceholderAPI", "Floodgate")) {
            api.hooks().unregister(owner, hook);
        }
    }

    private static void installItems(Plugin owner, LeDatPlatformApi api) {
        List.of(itemsAdder(owner), oraxen(owner), nexo(owner), mmoItems(owner)).forEach(adapter -> api.items().register(owner, adapter));
        api.hooks().register(owner, "CustomItems", api.items(), true,
                "Built-in adapters ready: ItemsAdder, Oraxen, Nexo, MMOItems (activated only when installed).");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void installVault(Plugin owner, LeDatPlatformApi api) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            return;
        }
        try {
            Class economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(economyClass);
            Object provider = registration == null ? null : registration.getProvider();
            if (provider == null) {
                owner.getLogger().warning("Vault is enabled but no Economy provider is registered.");
                return;
            }
            api.economy().register(owner, new VaultAdapter(provider));
            api.hooks().register(owner, "Vault", provider, true, "Built-in Vault economy adapter registered.");
        } catch (Throwable error) {
            owner.getLogger().warning("Could not initialize built-in Vault integration: " + error.getMessage());
        }
    }

    private static void installPlayerPoints(Plugin owner, LeDatPlatformApi api) {
        Plugin playerPoints = Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (playerPoints == null || !playerPoints.isEnabled()) {
            return;
        }
        try {
            Object pointsApi = playerPoints.getClass().getMethod("getAPI").invoke(playerPoints);
            api.economy().register(owner, new PlayerPointsAdapter(pointsApi));
            api.hooks().register(owner, "PlayerPoints", pointsApi, true, "Built-in PlayerPoints currency adapter registered.");
        } catch (Throwable error) {
            owner.getLogger().warning("Could not initialize built-in PlayerPoints adapter: " + error.getMessage());
        }
    }

    private static void installLuckPerms(Plugin owner, LeDatPlatformApi api) {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            return;
        }
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object luckPerms = providerClass.getMethod("get").invoke(null);
            api.hooks().register(owner, "LuckPerms", luckPerms, luckPerms != null,
                    "Built-in LuckPerms API provider available through HookRegistry.");
        } catch (Throwable error) {
            owner.getLogger().warning("Could not initialize built-in LuckPerms integration: " + error.getMessage());
        }
    }

    public static void installPlaceholderApi(Plugin owner, LeDatPlatformApi api) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        try {
            if (placeholderExpansion != null && isPlaceholderRegistered(placeholderExpansion)) {
                return;
            }
            if (placeholderExpansion != null) {
                ensureUnregisteredPlaceholder();
                placeholderExpansion = null;
            }

            CanonicalPlaceholderExpansion expansion = new CanonicalPlaceholderExpansion(api);
            boolean registered = expansion.register();
            if (registered) {
                placeholderExpansion = expansion;
            }
            api.hooks().register(owner, "PlaceholderAPI", expansion, registered,
                    registered ? "Built-in canonical %ledat_*% expansion registered." : "PlaceholderAPI rejected canonical expansion registration.");
        } catch (Throwable error) {
            owner.getLogger().warning("Could not initialize built-in PlaceholderAPI integration: " + error.getMessage());
        }
    }

    private static boolean isPlaceholderRegistered(Object expansion) {
        if (expansion == null) {
            return false;
        }
        try {
            Class<?> placeholderApi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Method method = placeholderApi.getMethod("isRegistered", PlaceholderExpansion.class);
            Object result = method.invoke(null, expansion);
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void ensureUnregisteredPlaceholder() {
        try {
            ((PlaceholderExpansion) placeholderExpansion).unregister();
        } catch (Throwable ignored) {
        }
    }

    private static void installFloodgate(Plugin owner, LeDatPlatformApi api) {
        boolean ready = api.forms().isAvailable();
        api.hooks().register(owner, "Floodgate", api.forms(), ready,
                ready ? "Built-in Floodgate/Geyser form bridge detected." : "Floodgate/Geyser form bridge unavailable.");
    }

    private static final class CanonicalPlaceholderExpansion extends PlaceholderExpansion {
        private final LeDatPlatformApi api;
        private CanonicalPlaceholderExpansion(LeDatPlatformApi api) { this.api = api; }
        @Override public @NotNull String getIdentifier() { return "ledat"; }
        @Override public @NotNull String getAuthor() { return "LeDat"; }
        @Override public @NotNull String getVersion() { return api.platformVersion(); }
        @Override public boolean persist() { return true; }
        @Override public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
            return api.placeholders().resolve(player, params).orElse("");
        }
    }

    private static final class VaultAdapter implements EconomyService.EconomyAdapter {
        private final Object provider;
        private VaultAdapter(Object provider) { this.provider = provider; }
        @Override public String id() { return "vault"; }
        @Override public String displayName() { return "Vault"; }
        @Override public boolean available() { return provider != null; }
        @Override public BigDecimal balance(OfflinePlayer player) { return decimal(call(provider, "getBalance", player)); }
        @Override public boolean has(OfflinePlayer player, BigDecimal amount) { return Boolean.TRUE.equals(call(provider, "has", player, amount(amount))); }
        @Override public EconomyService.TransactionResult withdraw(OfflinePlayer player, BigDecimal amount) { return transaction(call(provider, "withdrawPlayer", player, amount(amount)), amount); }
        @Override public EconomyService.TransactionResult deposit(OfflinePlayer player, BigDecimal amount) { return transaction(call(provider, "depositPlayer", player, amount(amount)), amount); }
        private static EconomyService.TransactionResult transaction(Object response, BigDecimal amount) {
            if (response == null) return EconomyService.TransactionResult.failed(amount, "Vault returned no response");
            try {
                boolean success = Boolean.TRUE.equals(method(response.getClass(), "transactionSuccess", new Object[0]).invoke(response));
                BigDecimal balance = decimal(field(response.getClass(), "balance").get(response));
                String error = String.valueOf(field(response.getClass(), "errorMessage").get(response));
                return new EconomyService.TransactionResult(success, amount, balance, success ? "" : error);
            } catch (Throwable error) {
                return EconomyService.TransactionResult.failed(amount, error.getMessage());
            }
        }
    }

    private record PlayerPointsAdapter(Object pointsApi) implements EconomyService.EconomyAdapter {
        @Override public String id() { return "playerpoints"; }
        @Override public String displayName() { return "PlayerPoints"; }
        @Override public boolean available() { return pointsApi != null; }
        @Override public BigDecimal balance(OfflinePlayer player) { return BigDecimal.valueOf(number("look", player)); }
        @Override public boolean has(OfflinePlayer player, BigDecimal amount) { return balance(player).compareTo(safe(amount)) >= 0; }
        @Override public EconomyService.TransactionResult withdraw(OfflinePlayer player, BigDecimal amount) { return change("take", player, amount); }
        @Override public EconomyService.TransactionResult deposit(OfflinePlayer player, BigDecimal amount) { return change("give", player, amount); }
        private EconomyService.TransactionResult change(String method, OfflinePlayer player, BigDecimal amount) {
            BigDecimal safe = safe(amount);
            try {
                boolean success = Boolean.TRUE.equals(method(pointsApi.getClass(), method, new Object[]{player.getUniqueId(), safe.intValueExact()})
                        .invoke(pointsApi, player.getUniqueId(), safe.intValueExact()));
                return new EconomyService.TransactionResult(success, safe, balance(player), success ? "" : "PlayerPoints rejected transaction");
            } catch (Throwable error) {
                return EconomyService.TransactionResult.failed(safe, error.getMessage());
            }
        }
        private long number(String method, OfflinePlayer player) {
            try { return ((Number) method(pointsApi.getClass(), method, new Object[]{player.getUniqueId()}).invoke(pointsApi, player.getUniqueId())).longValue(); }
            catch (Throwable ignored) { return 0L; }
        }
        private BigDecimal safe(BigDecimal amount) { return amount == null || amount.signum() < 0 ? BigDecimal.ZERO : amount; }
    }

    private abstract static class ItemAdapter implements CustomItemAdapter {
        private final Plugin owner;
        private final String provider;
        private final String pluginName;
        private ItemAdapter(Plugin owner, String provider, String pluginName) {
            this.owner = owner; this.provider = provider; this.pluginName = pluginName;
        }
        @Override public String provider() { return provider; }
        @Override public boolean available() { return Bukkit.getPluginManager().isPluginEnabled(pluginName); }
        @Override public Optional<ItemStack> create(CustomItemKey key, int amount) {
            if (!available()) return Optional.empty();
            try {
                ItemStack item = createItem(key);
                if (item == null || item.getType().isAir()) return Optional.empty();
                ItemStack copy = item.clone();
                copy.setAmount(Math.max(1, Math.min(copy.getMaxStackSize(), amount)));
                return Optional.of(copy);
            } catch (Throwable error) {
                owner.getLogger().fine("Could not create " + key.canonical() + " through " + pluginName + ": " + error.getMessage());
                return Optional.empty();
            }
        }
        @Override public Optional<CustomItemKey> identify(ItemStack item) {
            if (!available() || item == null || item.getType().isAir()) return Optional.empty();
            try {
                String id = identifyId(item);
                return id == null || id.isBlank() ? Optional.empty() : Optional.of(CustomItemKey.parse(provider + ":" + id));
            } catch (Throwable ignored) { return Optional.empty(); }
        }
        protected abstract ItemStack createItem(CustomItemKey key) throws Exception;
        protected abstract String identifyId(ItemStack item) throws Exception;
    }

    private static CustomItemAdapter itemsAdder(Plugin owner) {
        return new ItemAdapter(owner, "itemsadder", "ItemsAdder") {
            @Override protected ItemStack createItem(CustomItemKey key) throws Exception {
                Object item = callStatic("dev.lone.itemsadder.api.CustomStack", "getInstance", key.externalId());
                return item == null ? null : (ItemStack) call(item, "getItemStack");
            }
            @Override protected String identifyId(ItemStack item) throws Exception {
                Object custom = callStatic("dev.lone.itemsadder.api.CustomStack", "byItemStack", item);
                return custom == null ? null : String.valueOf(call(custom, "getNamespacedID"));
            }
        };
    }

    private static CustomItemAdapter oraxen(Plugin owner) {
        return new ItemAdapter(owner, "oraxen", "Oraxen") {
            @Override protected ItemStack createItem(CustomItemKey key) throws Exception {
                Object builder = callStatic("io.th0rgal.oraxen.api.OraxenItems", "getItemById", key.externalId());
                return builder == null ? null : (ItemStack) call(builder, "build");
            }
            @Override protected String identifyId(ItemStack item) throws Exception {
                Object id = callStatic("io.th0rgal.oraxen.api.OraxenItems", "getIdByItem", item);
                return id == null ? null : String.valueOf(id);
            }
        };
    }

    private static CustomItemAdapter nexo(Plugin owner) {
        return new ItemAdapter(owner, "nexo", "Nexo") {
            @Override protected ItemStack createItem(CustomItemKey key) throws Exception {
                Object builder = callStatic("com.nexomc.nexo.api.NexoItems", "itemFromId", key.externalId());
                return builder == null ? null : (ItemStack) call(builder, "build");
            }
            @Override protected String identifyId(ItemStack item) throws Exception {
                Object id = callStatic("com.nexomc.nexo.api.NexoItems", "idFromItem", item);
                return id == null ? null : String.valueOf(id);
            }
        };
    }

    private static CustomItemAdapter mmoItems(Plugin owner) {
        return new ItemAdapter(owner, "mmoitems", "MMOItems") {
            @Override protected ItemStack createItem(CustomItemKey key) throws Exception {
                if (key.namespace().isBlank()) return null;
                Object plugin = staticField("net.Indyuce.mmoitems.MMOItems", "plugin");
                Object type = call(call(plugin, "getTypes"), "get", key.namespace().toUpperCase(Locale.ROOT));
                return type == null ? null : (ItemStack) call(plugin, "getItem", type, key.id().toUpperCase(Locale.ROOT));
            }
            @Override protected String identifyId(ItemStack item) throws Exception {
                Object type = callStatic("net.Indyuce.mmoitems.MMOItems", "getTypeName", item);
                Object id = callStatic("net.Indyuce.mmoitems.MMOItems", "getID", item);
                return type == null || id == null ? null : type + ":" + id;
            }
        };
    }

    private static Object callStatic(String type, String method, Object... args) throws Exception {
        return method(Class.forName(type, true, BuiltInIntegrations.class.getClassLoader()), method, args).invoke(null, args);
    }
    private static Object staticField(String type, String field) throws Exception {
        Field value = field(Class.forName(type, true, BuiltInIntegrations.class.getClassLoader()), field);
        return value.get(null);
    }
    private static Object call(Object target, String method, Object... args) {
        if (target == null) return null;
        try { return method(target.getClass(), method, args).invoke(target, args); }
        catch (Throwable ignored) { return null; }
    }
    private static Method method(Class<?> type, String name, Object[] args) throws NoSuchMethodException {
        MethodSignature signature = new MethodSignature(name, Arrays.stream(args)
                .map(argument -> argument == null ? NullArgument.class : argument.getClass())
                .toList());
        Optional<Method> resolved = METHOD_CACHE.get(type).computeIfAbsent(signature, ignored -> findMethod(type, name, args));
        if (resolved.isPresent()) {
            return resolved.get();
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static Optional<Method> findMethod(Class<?> type, String name, Object[] args) {
        for (Method candidate : type.getMethods()) {
            if (!candidate.getName().equals(name) || candidate.getParameterCount() != args.length || !Modifier.isPublic(candidate.getModifiers())) continue;
            Class<?>[] parameters = candidate.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < parameters.length; i++) {
                if (args[i] != null && !wrap(parameters[i]).isInstance(args[i])) { compatible = false; break; }
            }
            if (compatible) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Optional<Field> resolved = FIELD_CACHE.get(type).computeIfAbsent(name, ignored -> {
            try {
                return Optional.of(type.getField(name));
            } catch (NoSuchFieldException missing) {
                return Optional.empty();
            }
        });
        if (resolved.isPresent()) {
            return resolved.get();
        }
        throw new NoSuchFieldException(type.getName() + "#" + name);
    }
    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }
    private static double amount(BigDecimal value) { return value == null ? 0D : Math.max(0D, value.doubleValue()); }
    private static BigDecimal decimal(Object value) {
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        try { return new BigDecimal(String.valueOf(value)); } catch (Exception ignored) { return BigDecimal.ZERO; }
    }

    private record MethodSignature(String name, List<Class<?>> argumentTypes) {
    }

    private static final class NullArgument {
        private NullArgument() {
        }
    }
}
