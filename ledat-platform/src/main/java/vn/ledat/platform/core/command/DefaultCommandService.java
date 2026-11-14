package vn.ledat.platform.core.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.command.CommandArgument;
import vn.ledat.platform.api.command.CommandArgumentType;
import vn.ledat.platform.api.command.CommandContext;
import vn.ledat.platform.api.command.CommandExecutor;
import vn.ledat.platform.api.command.CommandRoute;
import vn.ledat.platform.api.command.CommandService;
import vn.ledat.platform.api.command.CommandStats;
import vn.ledat.platform.api.command.CommandTabProvider;
import vn.ledat.platform.api.cooldown.CooldownService;
import vn.ledat.platform.api.cooldown.RateLimitResult;
import vn.ledat.platform.api.cooldown.RateLimitService;
import vn.ledat.platform.api.language.LanguageService;
import vn.ledat.platform.api.scheduler.LeDatScheduler;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

/**
 * Paper Brigadier-backed implementation of LeDatPlatform's suite command registry.
 *
 * <p>The public CommandService contract stays Bukkit-friendly for existing LeDat
 * child plugins, but registration is now performed through Paper's lifecycle
 * command registrar. Existing handlers receive the same CommandContext payload as
 * before, while clients get a real Brigadier command tree, literal subcommands,
 * aliases, and dynamic suggestions.</p>
 */
@SuppressWarnings("UnstableApiUsage")
public final class DefaultCommandService implements CommandService {
    private static final List<String> MATERIAL_SUGGESTIONS = Arrays.stream(Material.values())
            .map(material -> material.name().toLowerCase(Locale.ROOT))
            .toList();
    private static final List<String> LANGUAGE_SUGGESTIONS = List.of("vi_VN", "en_US", "zh_CN", "vi", "en", "zh");
    private static final List<String> DURATION_SUGGESTIONS = List.of("10s", "30s", "1m", "5m", "1h", "1d");
    private static final long SUGGESTION_CACHE_MILLIS = 5000L;

    private final Plugin platformPlugin;
    private final LanguageService languageService;
    private final CooldownService cooldownService;
    private final RateLimitService rateLimitService;
    private final LeDatScheduler scheduler;
    private final Map<String, CommandRoute> routes = new ConcurrentHashMap<>();
    private final Map<String, Builder> activeRoots = new ConcurrentHashMap<>();
    private final Set<String> lifecycleHandlers = ConcurrentHashMap.newKeySet();
    private final AtomicLong executedHandlers = new AtomicLong();
    private final AtomicLong slowHandlers = new AtomicLong();
    private final Map<String, Long> slowHandlerWarnings = new ConcurrentHashMap<>();
    private final Map<String, Long> commandErrorWarnings = new ConcurrentHashMap<>();
    private final Map<String, Long> commandErrorFeedback = new ConcurrentHashMap<>();
    private volatile List<String> pluginSuggestions = List.of();
    private volatile long pluginSuggestionsAt;
    private volatile List<String> worldSuggestions = List.of();
    private volatile long worldSuggestionsAt;
    private volatile List<String> permissionSuggestions = List.of();
    private volatile long permissionSuggestionsAt;

    public DefaultCommandService(Plugin platformPlugin, LanguageService languageService, CooldownService cooldownService, RateLimitService rateLimitService) {
        this(platformPlugin, languageService, cooldownService, rateLimitService, null);
    }

    public DefaultCommandService(Plugin platformPlugin, LanguageService languageService, CooldownService cooldownService, RateLimitService rateLimitService, LeDatScheduler scheduler) {
        if (platformPlugin == null) {
            throw new IllegalArgumentException("platformPlugin cannot be null");
        }
        this.platformPlugin = platformPlugin;
        this.languageService = languageService;
        this.cooldownService = cooldownService;
        this.rateLimitService = rateLimitService;
        this.scheduler = scheduler;
    }

    @Override
    public CommandBuilder command(Plugin plugin, String name) {
        return new Builder(plugin, name, null);
    }

    @Override
    public Map<String, CommandRoute> routes() {
        return Map.copyOf(routes);
    }

    @Override
    public CommandStats stats() {
        Map<String, CommandRoute> snapshot = routes();
        long roots = snapshot.values().stream().filter(route -> route.path().equals(route.rootName())).count();
        long legacy = snapshot.values().stream().filter(CommandRoute::legacyDelegate).count();
        long missing = snapshot.values().stream().filter(route -> !route.declaredInPluginYml()).count();
        long playerOnly = snapshot.values().stream().filter(CommandRoute::playerOnly).count();
        long cooldown = snapshot.values().stream().filter(route -> route.cooldownSeconds() > 0).count();
        long rateLimited = snapshot.values().stream().filter(route -> route.rateLimitMaxActions() > 0 && route.rateLimitWindowMillis() > 0).count();
        long plugins = snapshot.values().stream().map(CommandRoute::pluginName).distinct().count();
        return new CommandStats(
                safeInt(roots),
                snapshot.size(),
                safeInt(legacy),
                safeInt(missing),
                safeInt(playerOnly),
                safeInt(cooldown),
                safeInt(rateLimited),
                safeInt(plugins),
                executedHandlers.get(),
                slowHandlers.get(),
                Instant.now()
        );
    }

    @Override
    public void unregisterPlugin(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        String owner = plugin.getName().toLowerCase(Locale.ROOT);
        routes.keySet().removeIf(key -> key.startsWith(owner + ":"));
        activeRoots.keySet().removeIf(key -> key.startsWith(owner + ":"));
    }

    private final class Builder implements CommandBuilder {
        private final Plugin plugin;
        private final String name;
        private final Builder parent;
        private String permission = "";
        private String usage = "";
        private String usageKey = "";
        private String description = "";
        private boolean playerOnly;
        private long cooldownSeconds;
        private int minArgs = 0;
        private boolean minArgsConfigured;
        private int maxArgs = -1;
        private boolean maxArgsConfigured;
        private int rateLimitMaxActions = 0;
        private Duration rateLimitWindow = Duration.ZERO;
        private CommandExecutor executor = context -> sendUsage(context, this);
        private boolean executorConfigured;
        private boolean legacyDelegate;
        private String legacyDetail = "";
        private CommandTabProvider tabProvider = context -> List.of();
        private boolean tabConfigured;
        private final List<CommandArgument> arguments = new ArrayList<>();
        private final Set<String> aliases = new LinkedHashSet<>();
        private final Map<String, CommandTabProvider> argumentSuggestions = new LinkedHashMap<>();
        private final Map<String, Builder> children = new LinkedHashMap<>();

        private Builder(Plugin plugin, String name, Builder parent) {
            if (plugin == null) {
                throw new IllegalArgumentException("plugin cannot be null");
            }
            this.plugin = plugin;
            this.name = sanitizeName(name);
            this.parent = parent;
            if (this.name.isBlank()) {
                throw new IllegalArgumentException("command/subcommand name cannot be blank");
            }
        }

        @Override public CommandBuilder permission(String permission) { this.permission = permission == null ? "" : permission.trim(); return this; }
        @Override public CommandBuilder usage(String usage) { this.usage = usage == null ? "" : usage.trim(); return this; }
        @Override public CommandBuilder usageKey(String key) { this.usageKey = key == null ? "" : key.trim(); return this; }
        @Override public CommandBuilder description(String description) { this.description = description == null ? "" : description.trim(); return this; }
        @Override public CommandBuilder playerOnly(boolean playerOnly) { this.playerOnly = playerOnly; return this; }
        @Override public CommandBuilder cooldownSeconds(long seconds) { this.cooldownSeconds = Math.max(0L, seconds); return this; }
        @Override public CommandBuilder aliases(String... aliases) {
            if (aliases != null) {
                Arrays.stream(aliases).map(DefaultCommandService.this::sanitizeName).filter(alias -> !alias.isBlank() && !alias.equals(name)).forEach(this.aliases::add);
            }
            return this;
        }
        @Override public CommandBuilder suggestions(String argumentName, CommandTabProvider provider) {
            String key = sanitizeName(argumentName);
            if (!key.isBlank()) {
                if (provider == null) argumentSuggestions.remove(key); else argumentSuggestions.put(key, provider);
            }
            return this;
        }
        @Override public CommandBuilder legacyDelegate(boolean legacy) { this.legacyDelegate = legacy; if (!legacy) { this.legacyDetail = ""; } return this; }
        @Override public CommandBuilder legacyDelegate(String detail) { this.legacyDelegate = true; this.legacyDetail = detail == null ? "" : detail.trim(); return this; }
        @Override public CommandBuilder minArgs(int minArgs) { this.minArgs = Math.max(0, minArgs); this.minArgsConfigured = true; return this; }
        @Override public CommandBuilder maxArgs(int maxArgs) { this.maxArgs = maxArgs < 0 ? -1 : maxArgs; this.maxArgsConfigured = true; return this; }
        @Override public CommandBuilder rateLimit(int maxActions, Duration window) {
            this.rateLimitMaxActions = Math.max(0, maxActions);
            this.rateLimitWindow = window == null ? Duration.ZERO : window;
            return this;
        }
        @Override public CommandBuilder argument(CommandArgument argument) {
            if (argument != null) {
                this.arguments.add(argument);
            }
            return this;
        }
        @Override public CommandBuilder executor(CommandExecutor executor) { this.executor = executor == null ? context -> sendUsage(context, this) : executor; this.executorConfigured = executor != null; return this; }
        @Override public CommandBuilder tab(CommandTabProvider provider) { this.tabProvider = provider == null ? context -> List.of() : provider; this.tabConfigured = provider != null; return this; }

        @Override
        public CommandBuilder subCommand(String name, Consumer<CommandBuilder> configurer) {
            Builder child = new Builder(plugin, name, this);
            if (configurer != null) {
                configurer.accept(child);
            }
            Builder previous = children.put(child.name, child);
            if (previous != null) {
                plugin.getLogger().warning("Duplicate platform subcommand /" + root(child).name + " " + child.path() + " replaced in " + plugin.getName());
            }
            return this;
        }

        @Override
        public void register() {
            Builder commandRoot = root(this);
            String key = rootKey(commandRoot.plugin, commandRoot.name);
            unregisterRoutes(commandRoot.plugin, commandRoot.name);
            activeRoots.put(key, commandRoot);
            collectRoutes(commandRoot, true);
            bindBukkitFallback(commandRoot);
            registerLifecycleHandlerOnce(commandRoot.plugin, key);
        }

        private String path() {
            if (parent == null) {
                return name;
            }
            return parent.path() + " " + name;
        }

        private List<Builder> lineage() {
            List<Builder> chain = new ArrayList<>();
            Builder cursor = this;
            while (cursor != null) {
                chain.add(0, cursor);
                cursor = cursor.parent;
            }
            return chain;
        }
    }

    private void registerLifecycleHandlerOnce(Plugin plugin, String key) {
        if (!lifecycleHandlers.add(key)) {
            return;
        }
        try {
            plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> registerNativeCommand(key, event.registrar()));
        } catch (Throwable error) {
            Builder root = activeRoots.get(key);
            if (root != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to attach Paper command lifecycle handler for /" + root.name, error);
                collectRoutes(root, false);
            }
        }
    }

    private void registerNativeCommand(String key, Commands registrar) {
        Builder root = activeRoots.get(key);
        if (root == null) {
            return;
        }
        try {
            LiteralCommandNode<CommandSourceStack> node = toLiteral(root).build();
            Collection<String> aliases = aliases(root);
            String description = root.description.isBlank() ? null : root.description;
            Set<String> registeredLabels = registrar.register(node, description, aliases);
            boolean registered = registeredLabels != null && !registeredLabels.isEmpty();
            collectRoutes(root, registered);
            if (!registered) {
                root.plugin.getLogger().warning("Paper Brigadier registrar returned no labels for /" + root.name + "; command will be reported as UNREGISTERED.");
            } else if (platformPlugin.getConfig().getBoolean("command.paper-native.log-registration", false)) {
                root.plugin.getLogger().info("Registered Paper Brigadier command /" + root.name + " labels=" + registeredLabels + (aliases.isEmpty() ? "" : " aliases=" + aliases));
            }
        } catch (Throwable error) {
            root.plugin.getLogger().log(Level.SEVERE, "Failed to register Paper Brigadier command /" + root.name, error);
            collectRoutes(root, false);
        }
    }

    private LiteralArgumentBuilder<CommandSourceStack> toLiteral(Builder node) {
        return toLiteral(node, node.name);
    }

    private LiteralArgumentBuilder<CommandSourceStack> toLiteral(Builder node, String literalName) {
        LiteralArgumentBuilder<CommandSourceStack> literal = Commands.literal(literalName);
        if (paperNativeRequirements()) {
            literal.requires(source -> canSee(source.getSender(), node));
        }
        if (isValidArgCountForNode(node, 0) || !node.children.isEmpty()) {
            literal.executes(context -> executeFromNode(node, context, new String[0]));
        }
        for (Builder child : node.children.values()) {
            literal.then(toLiteral(child));
            for (String alias : child.aliases) {
                literal.then(toLiteral(child, alias));
            }
        }
        if (!argumentSpecs(node).isEmpty()) {
            literal.then(typedArgument(node, 0));
        }
        if (shouldAttachRawArgument(node)) {
            literal.then(rawArgument(node));
        }
        return literal;
    }



    private List<CommandArgument> argumentSpecs(Builder node) {
        if (!node.arguments.isEmpty()) {
            return node.arguments;
        }
        if (!node.children.isEmpty() || !inferUsageArguments()) {
            return List.of();
        }
        return parseUsageArguments(node.usage, node.path());
    }

    private int effectiveMinArgs(Builder node) {
        if (node.minArgsConfigured) {
            return node.minArgs;
        }
        List<CommandArgument> arguments = argumentSpecs(node);
        if (!arguments.isEmpty()) {
            return (int) arguments.stream().filter(CommandArgument::required).count();
        }
        return node.minArgs;
    }

    private int effectiveMaxArgs(Builder node) {
        if (node.maxArgsConfigured) {
            return node.maxArgs;
        }
        List<CommandArgument> arguments = argumentSpecs(node);
        if (!arguments.isEmpty()) {
            return arguments.size();
        }
        return node.maxArgs;
    }

    private List<CommandArgument> parseUsageArguments(String usage, String path) {
        if (usage == null || usage.isBlank()) {
            return List.of();
        }
        List<String> tokens = Arrays.stream(usage.trim().split("\\s+"))
                .filter(token -> !token.isBlank())
                .toList();
        if (tokens.size() <= 1) {
            return List.of();
        }
        String[] pathParts = path == null || path.isBlank() ? new String[0] : path.split("\\s+");
        int start = 1;
        while (start < tokens.size() && start <= pathParts.length) {
            String token = stripCommandPrefix(tokens.get(start));
            String expected = sanitizeName(pathParts[start - 1]);
            if (!expected.isBlank() && sanitizeName(token).equals(expected)) {
                start++;
                continue;
            }
            break;
        }
        List<CommandArgument> arguments = new ArrayList<>();
        List<String> rawArguments = new ArrayList<>();
        for (int i = start; i < tokens.size(); i++) {
            String token = tokens.get(i).trim();
            if (token.length() < 3) {
                continue;
            }
            boolean required = token.startsWith("<") && token.endsWith(">");
            boolean optional = token.startsWith("[") && token.endsWith("]");
            if (!required && !optional) {
                continue;
            }
            String raw = token.substring(1, token.length() - 1).trim();
            String rawLower = raw.toLowerCase(Locale.ROOT);
            if (rawLower.contains("subcommand")) {
                return List.of();
            }
            if (raw.isBlank() || raw.contains("=") || raw.contains(":")) {
                continue;
            }
            rawArguments.add(raw);
            arguments.add(inferUsageArgument(raw, required));
        }
        if (rawArguments.size() == 1) {
            String raw = rawArguments.get(0).toLowerCase(Locale.ROOT);
            long choiceSeparators = raw.chars().filter(ch -> ch == '|').count();
            if (choiceSeparators >= 5 || raw.contains("admin") || raw.contains("<") || raw.contains(">")) {
                // Usually a legacy root subcommand menu such as [create|join|chat|...]
                // or [admin ...]. Keep greedy raw routing until that command is split
                // into real child nodes.
                return List.of();
            }
        }
        return List.copyOf(arguments);
    }

    private CommandArgument inferUsageArgument(String raw, boolean required) {
        String normalized = raw.replace("...", "").replace("/", "|").trim();
        String[] alternatives = Arrays.stream(normalized.split("\\|"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toArray(String[]::new);
        String primary = alternatives.length == 0 ? normalized : alternatives[0];
        String lower = primary.toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        if (alternatives.length > 1) {
            for (String alternative : alternatives) {
                String alt = alternative.trim();
                if (!alt.equalsIgnoreCase("player") && !alt.equalsIgnoreCase("target") && !alt.equalsIgnoreCase("id") && !alt.equalsIgnoreCase("amount") && !alt.equalsIgnoreCase("name")) {
                    suggestions.add(alt);
                }
            }
        }
        CommandArgumentType type = inferType(lower, alternatives.length > 1, raw.contains("..."));
        String name = primary.replaceAll("[^A-Za-z0-9_-]", "_");
        if (name.isBlank()) {
            name = "arg";
        }
        return new CommandArgument(name, type, suggestions, CommandArgument.NO_MIN, CommandArgument.NO_MAX, required);
    }

    private CommandArgumentType inferType(String lower, boolean hasChoices, boolean greedy) {
        if (greedy || lower.contains("message") || lower.contains("reason") || lower.contains("text")) {
            return CommandArgumentType.GREEDY_STRING;
        }
        if (lower.contains("uuid")) {
            return CommandArgumentType.UUID;
        }
        if (lower.contains("player") || lower.contains("target")) {
            return lower.contains("offline") ? CommandArgumentType.OFFLINE_PLAYER_NAME : CommandArgumentType.PLAYER_NAME;
        }
        if (lower.contains("plugin")) {
            return CommandArgumentType.PLUGIN_NAME;
        }
        if (lower.contains("server") || lower.contains("node")) {
            return CommandArgumentType.SERVER_NAME;
        }
        if (lower.contains("permission")) {
            return CommandArgumentType.PERMISSION;
        }
        if (lower.contains("world")) {
            return CommandArgumentType.WORLD_NAME;
        }
        if (lower.contains("material") || lower.contains("item")) {
            return CommandArgumentType.MATERIAL_NAME;
        }
        if (lower.equals("lang") || lower.contains("language")) {
            return CommandArgumentType.LANGUAGE;
        }
        if (lower.contains("duration") || lower.equals("time-window")) {
            return CommandArgumentType.DURATION;
        }
        if (lower.contains("money") || lower.contains("balance") || lower.contains("price") || lower.contains("cost")) {
            return CommandArgumentType.MONEY;
        }
        if (lower.contains("count") || lower.equals("page") || lower.contains("limit") || lower.contains("radius") || lower.contains("seconds") || lower.contains("minutes")
                || lower.contains("amount") || lower.contains("multiplier") || lower.contains("level")) {
            return CommandArgumentType.INTEGER;
        }
        return hasChoices ? CommandArgumentType.ENUM : CommandArgumentType.WORD;
    }

    private String stripCommandPrefix(String token) {
        if (token == null) {
            return "";
        }
        return token.startsWith("/") ? token.substring(1) : token;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private RequiredArgumentBuilder<CommandSourceStack, ?> typedArgument(Builder node, int index) {
        CommandArgument spec = argumentSpecs(node).get(index);
        RequiredArgumentBuilder argument = Commands.argument(spec.name(), brigadierType(spec));
        argument.suggests((context, builder) -> suggestTypedArgument(node, index, context, builder));
        int argc = index + 1;
        if (isValidArgCountForNode(node, argc)) {
            argument.executes(context -> executeFromTypedArguments(node, context, argc));
        }
        if (index + 1 < argumentSpecs(node).size()) {
            argument.then(typedArgument(node, index + 1));
        }
        return argument;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentType brigadierType(CommandArgument spec) {
        return switch (spec.type()) {
            case INTEGER -> {
                int min = spec.min() == CommandArgument.NO_MIN ? Integer.MIN_VALUE : spec.min();
                int max = spec.max() == CommandArgument.NO_MAX ? Integer.MAX_VALUE : spec.max();
                yield IntegerArgumentType.integer(min, max);
            }
            case LONG -> {
                yield LongArgumentType.longArg(spec.effectiveLongMin(), spec.effectiveLongMax());
            }
            case DOUBLE, MONEY -> DoubleArgumentType.doubleArg(spec.effectiveDoubleMin(), spec.effectiveDoubleMax());
            case BOOLEAN -> BoolArgumentType.bool();
            case STRING -> StringArgumentType.string();
            case GREEDY_STRING -> StringArgumentType.greedyString();
            case WORD, UUID, DURATION, PLAYER_NAME, OFFLINE_PLAYER_NAME, PLUGIN_NAME, SERVER_NAME, PERMISSION, WORLD_NAME, MATERIAL_NAME, CUSTOM_ITEM_KEY, LANGUAGE, ENUM -> StringArgumentType.word();
        };
    }

    private int executeFromTypedArguments(Builder node, com.mojang.brigadier.context.CommandContext<CommandSourceStack> brigadierContext, int count) {
        executeNode(node, new CommandContext(node.plugin, brigadierContext.getSource().getSender(), label(brigadierContext, node), typedArgs(node, brigadierContext, count)));
        return SINGLE_SUCCESS;
    }

    private String[] typedArgs(Builder node, com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, int count) {
        List<String> args = new ArrayList<>(Math.max(0, count));
        int limit = Math.min(count, argumentSpecs(node).size());
        for (int i = 0; i < limit; i++) {
            args.add(readTypedArgument(argumentSpecs(node).get(i), context));
        }
        return args.toArray(String[]::new);
    }

    private String readTypedArgument(CommandArgument spec, com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        try {
            return switch (spec.type()) {
                case INTEGER -> String.valueOf(IntegerArgumentType.getInteger(context, spec.name()));
                case LONG -> String.valueOf(LongArgumentType.getLong(context, spec.name()));
                case DOUBLE, MONEY -> String.valueOf(DoubleArgumentType.getDouble(context, spec.name()));
                case BOOLEAN -> String.valueOf(BoolArgumentType.getBool(context, spec.name()));
                case WORD, STRING, GREEDY_STRING, UUID, DURATION, PLAYER_NAME, OFFLINE_PLAYER_NAME, PLUGIN_NAME, SERVER_NAME, PERMISSION, WORLD_NAME, MATERIAL_NAME, CUSTOM_ITEM_KEY, LANGUAGE, ENUM -> StringArgumentType.getString(context, spec.name());
            };
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean isValidArgCountForNode(Builder node, int count) {
        int max = effectiveMaxArgs(node);
        return count >= effectiveMinArgs(node) && (max < 0 || count <= max);
    }

    private CompletableFuture<Suggestions> suggestTypedArgument(Builder node, int index, com.mojang.brigadier.context.CommandContext<CommandSourceStack> brigadierContext, SuggestionsBuilder builder) {
        CommandSender sender = brigadierContext.getSource().getSender();
        CommandArgument spec = argumentSpecs(node).get(index);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.addAll(staticSuggestions(node, spec, sender));
        CommandTabProvider argumentProvider = node.argumentSuggestions.get(sanitizeName(spec.name()));
        if (canSee(sender, node) && argumentProvider != null) {
            try {
                candidates.addAll(argumentProvider.complete(new CommandContext(node.plugin, sender, label(brigadierContext, node), typedCompletionArgs(node, index, brigadierContext, builder))));
            } catch (Throwable error) {
                node.plugin.getLogger().log(Level.FINE, "Typed argument suggestions failed for /" + node.path() + " arg=" + spec.name(), error);
            }
        }
        if (canSee(sender, node) && node.tabConfigured) {
            try {
                candidates.addAll(node.tabProvider.complete(new CommandContext(node.plugin, sender, label(brigadierContext, node), typedCompletionArgs(node, index, brigadierContext, builder))));
            } catch (Throwable error) {
                node.plugin.getLogger().log(Level.FINE, "Paper Brigadier typed suggestions failed for /" + label(brigadierContext, node) + " path=" + node.path() + " arg=" + spec.name(), error);
            }
        }
        return suggestCandidates(builder, candidates);
    }

    private String[] typedCompletionArgs(Builder node, int index, com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        List<String> args = new ArrayList<>(index + 1);
        for (int i = 0; i < index; i++) {
            args.add(readTypedArgument(argumentSpecs(node).get(i), context));
        }
        args.add(builder.getRemaining());
        return args.toArray(String[]::new);
    }

    private List<String> staticSuggestions(Builder node, CommandArgument spec, CommandSender sender) {
        Set<String> values = new LinkedHashSet<>(spec.suggestions());
        switch (spec.type()) {
            case BOOLEAN -> values.addAll(List.of("true", "false"));
            case PLAYER_NAME, OFFLINE_PLAYER_NAME -> Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(values::add);
            case PLUGIN_NAME -> values.addAll(cachedPluginSuggestions());
            case WORLD_NAME -> values.addAll(cachedWorldSuggestions());
            case MATERIAL_NAME -> values.addAll(MATERIAL_SUGGESTIONS);
            case CUSTOM_ITEM_KEY -> {
                try {
                    vn.ledat.platform.api.LeDatPlatformProvider.optional().ifPresent(api -> values.addAll(api.items().suggestions("")));
                } catch (Throwable ignored) {
                }
            }
            case LANGUAGE -> values.addAll(LANGUAGE_SUGGESTIONS);
            case DURATION -> values.addAll(DURATION_SUGGESTIONS);
            case PERMISSION -> values.addAll(cachedPermissionSuggestions());
            default -> { }
        }
        return values.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> cachedPluginSuggestions() {
        long now = System.currentTimeMillis();
        List<String> cached = pluginSuggestions;
        if (!cached.isEmpty() && now - pluginSuggestionsAt < SUGGESTION_CACHE_MILLIS) {
            return cached;
        }
        List<String> refreshed = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .map(Plugin::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        pluginSuggestions = refreshed;
        pluginSuggestionsAt = now;
        return refreshed;
    }

    private List<String> cachedWorldSuggestions() {
        long now = System.currentTimeMillis();
        List<String> cached = worldSuggestions;
        if (!cached.isEmpty() && now - worldSuggestionsAt < SUGGESTION_CACHE_MILLIS) {
            return cached;
        }
        List<String> refreshed = Bukkit.getWorlds().stream()
                .map(World::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        worldSuggestions = refreshed;
        worldSuggestionsAt = now;
        return refreshed;
    }

    private List<String> cachedPermissionSuggestions() {
        long now = System.currentTimeMillis();
        List<String> cached = permissionSuggestions;
        if (!cached.isEmpty() && now - permissionSuggestionsAt < SUGGESTION_CACHE_MILLIS) {
            return cached;
        }
        List<String> refreshed = Bukkit.getPluginManager().getPermissions().stream()
                .map(Permission::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        permissionSuggestions = refreshed;
        permissionSuggestionsAt = now;
        return refreshed;
    }

    private CompletableFuture<Suggestions> suggestCandidates(SuggestionsBuilder builder, Collection<String> candidates) {
        String token = builder.getRemaining().toLowerCase(Locale.ROOT);
        int emitted = 0;
        int max = maxSuggestions();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String value = candidate.trim();
            if (token.isBlank() || value.toLowerCase(Locale.ROOT).startsWith(token)) {
                builder.suggest(value);
                emitted++;
                if (emitted >= max) {
                    break;
                }
            }
        }
        return builder.buildFuture();
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> rawArgument(Builder node) {
        return Commands.argument("args", StringArgumentType.greedyString())
                .suggests((context, builder) -> suggestRawArguments(node, context, builder))
                .executes(context -> executeFromRawArgument(node, context));
    }

    private boolean shouldAttachRawArgument(Builder node) {
        if (!argumentSpecs(node).isEmpty()) {
            return false;
        }
        return !node.children.isEmpty()
                || node.tabConfigured
                || node.minArgs > 0
                || node.maxArgs != 0
                || legacyDelegate(node);
    }

    private int executeFromNode(Builder node, com.mojang.brigadier.context.CommandContext<CommandSourceStack> brigadierContext, String[] args) {
        executeNode(node, new CommandContext(node.plugin, brigadierContext.getSource().getSender(), label(brigadierContext, node), args));
        return SINGLE_SUCCESS;
    }

    private int executeFromRawArgument(Builder node, com.mojang.brigadier.context.CommandContext<CommandSourceStack> brigadierContext) {
        String raw = StringArgumentType.getString(brigadierContext, "args");
        String[] args = splitForExecution(raw);
        CommandSender sender = brigadierContext.getSource().getSender();
        executeResolved(node, sender, label(brigadierContext, node), args);
        return SINGLE_SUCCESS;
    }

    private void bindBukkitFallback(Builder root) {
        if (root == null || root.plugin == null) {
            return;
        }
        PluginCommand command = root.plugin.getServer().getPluginCommand(root.name);
        if (command == null || !command.getPlugin().equals(root.plugin)) {
            return;
        }
        command.setExecutor((sender, bukkitCommand, label, args) -> {
            executeResolved(root, sender, label == null || label.isBlank() ? root.name : label, args == null ? new String[0] : args);
            return true;
        });
        command.setTabCompleter((sender, bukkitCommand, alias, args) ->
                completeResolved(root, sender, alias == null || alias.isBlank() ? root.name : alias, args == null ? new String[0] : args));
    }

    private void executeResolved(Builder root, CommandSender sender, String label, String[] args) {
        ResolvedCommand resolved = resolveCommand(root, args);
        if (resolved.unknownArgument() != null) {
            sendCommandMessage(sender, root.plugin, "unknown-message-key", "command.unknown", Map.of(
                    "argument", resolved.unknownArgument(),
                    "usage", usage(resolved.node(), label)
            ));
            return;
        }
        executeNode(resolved.node(), new CommandContext(resolved.node().plugin, sender, label, resolved.remainingArgs()));
    }

    private List<String> completeResolved(Builder root, CommandSender sender, String label, String[] args) {
        if (args == null || args.length == 0) {
            return completeNode(root, sender, label, new String[]{""});
        }
        Builder cursor = root;
        int index = 0;
        while (index < args.length - 1) {
            Builder child = child(cursor, args[index]);
            if (child == null || !canSee(sender, child)) {
                break;
            }
            cursor = child;
            index++;
        }
        String[] remaining = Arrays.copyOfRange(args, index, args.length);
        return completeNode(cursor, sender, label, remaining);
    }

    private List<String> completeNode(Builder node, CommandSender sender, String label, String[] args) {
        Set<String> candidates = new LinkedHashSet<>();
        String token = args.length == 0 ? "" : args[0];
        if (args.length <= 1) {
            for (Map.Entry<String, Builder> entry : node.children.entrySet()) {
                if (canSee(sender, entry.getValue())) {
                    candidates.add(entry.getKey());
                    candidates.addAll(entry.getValue().aliases);
                }
            }
        }
        if (canSee(sender, node)) {
            try {
                candidates.addAll(node.tabProvider.complete(new CommandContext(node.plugin, sender, label, args)));
            } catch (Throwable error) {
                node.plugin.getLogger().log(Level.FINE, "Bukkit command suggestions failed for /" + label + " path=" + node.path(), error);
            }
        }
        String lower = token == null ? "" : token.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate != null && !candidate.isBlank())
                .filter(candidate -> lower.isBlank() || candidate.toLowerCase(Locale.ROOT).startsWith(lower))
                .limit(maxSuggestions())
                .toList();
    }

    private ResolvedCommand resolveCommand(Builder root, String[] args) {
        String[] safeArgs = args == null ? new String[0] : args;
        Builder cursor = root;
        int consumed = 0;
        while (consumed < safeArgs.length) {
            Builder child = child(cursor, safeArgs[consumed]);
            if (child == null) {
                break;
            }
            cursor = child;
            consumed++;
        }
        if (consumed < safeArgs.length && !cursor.children.isEmpty() && (!cursor.executorConfigured || cursor.maxArgs == 0)) {
            return new ResolvedCommand(cursor, Arrays.copyOfRange(safeArgs, consumed, safeArgs.length), safeArgs[consumed]);
        }
        return new ResolvedCommand(cursor, Arrays.copyOfRange(safeArgs, consumed, safeArgs.length), null);
    }

    private CompletableFuture<Suggestions> suggestRawArguments(Builder node, com.mojang.brigadier.context.CommandContext<CommandSourceStack> brigadierContext, SuggestionsBuilder builder) {
        CommandSender sender = brigadierContext.getSource().getSender();
        String remaining = builder.getRemaining();
        String base = prefixBeforeCurrentToken(remaining);
        String token = currentToken(remaining).toLowerCase(Locale.ROOT);
        Set<String> candidates = new LinkedHashSet<>();
        if (!remaining.contains(" ")) {
            for (Map.Entry<String, Builder> entry : node.children.entrySet()) {
                if (canSee(sender, entry.getValue())) {
                    candidates.add(entry.getKey());
                    candidates.addAll(entry.getValue().aliases);
                }
            }
        }
        if (canSee(sender, node)) {
            try {
                candidates.addAll(node.tabProvider.complete(new CommandContext(node.plugin, sender, label(brigadierContext, node), splitForCompletion(remaining))));
            } catch (Throwable error) {
                node.plugin.getLogger().log(Level.FINE, "Paper Brigadier suggestions failed for /" + label(brigadierContext, node) + " path=" + node.path(), error);
            }
        }
        if (!node.children.isEmpty() && !remaining.contains(" ")) {
            candidates.removeIf(candidate -> isHiddenChildSuggestion(node, sender, candidate));
        }
        int emitted = 0;
        int max = maxSuggestions();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String normalized = candidate.toLowerCase(Locale.ROOT);
            if (token.isBlank() || normalized.startsWith(token)) {
                builder.suggest(base + candidate);
                emitted++;
                if (emitted >= max) {
                    break;
                }
            }
        }
        return builder.buildFuture();
    }


    private boolean isHiddenChildSuggestion(Builder node, CommandSender sender, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        Builder child = child(node, candidate);
        return child != null && !canSee(sender, child);
    }

    private Builder child(Builder node, String token) {
        String normalized = sanitizeName(token);
        Builder direct = node.children.get(normalized);
        if (direct != null) {
            return direct;
        }
        return node.children.values().stream().filter(candidate -> candidate.aliases.contains(normalized)).findFirst().orElse(null);
    }

    private void executeNode(Builder node, CommandContext context) {
        Runnable action = () -> executeNodeDirect(node, context);
        if (scheduler == null || (!scheduler.isFolia() && scheduler.isPrimaryThread())) {
            action.run();
            return;
        }
        try {
            scheduler.sender(node.plugin, context.sender(), action);
        } catch (Throwable error) {
            node.plugin.getLogger().log(Level.WARNING, "Could not route command /" + context.label() + " through scheduler; running inline as fallback", error);
            action.run();
        }
    }

    private void executeNodeDirect(Builder node, CommandContext context) {
        for (String permission : requiredPermissions(node)) {
            if (!context.sender().isOp() && !context.sender().hasPermission(permission)) {
                sendCommandMessage(context.sender(), node.plugin, "no-permission-message-key", "command.no-permission", Map.of("permission", permission));
                return;
            }
        }
        if (requiresPlayer(node) && !(context.sender() instanceof Player)) {
            sendCommandMessage(context.sender(), node.plugin, "player-only-message-key", "command.player-only", Map.of());
            return;
        }
        if (!validateArgs(node, context)) {
            return;
        }
        UUID subject = subject(context.sender());
        String action = "command:" + node.path().replace(' ', ':');
        if (node.cooldownSeconds > 0) {
            if (!cooldownService.tryAcquire(node.plugin.getName(), subject, action, Duration.ofSeconds(node.cooldownSeconds))) {
                long seconds = Math.max(1L, cooldownService.remaining(node.plugin.getName(), subject, action).toSeconds());
                sendCommandMessage(context.sender(), node.plugin, "cooldown-message-key", "command.cooldown", Map.of("time", String.valueOf(seconds)));
                return;
            }
        }
        if (node.rateLimitMaxActions > 0 && node.rateLimitWindow != null && !node.rateLimitWindow.isZero() && !node.rateLimitWindow.isNegative()) {
            RateLimitResult result = rateLimitService.tryAcquire(node.plugin.getName(), subject, action, node.rateLimitMaxActions, node.rateLimitWindow);
            if (!result.allowed()) {
                long seconds = Math.max(1L, result.retryAfter().toSeconds());
                sendCommandMessage(context.sender(), node.plugin, "rate-limited-message-key", "command.rate-limited", Map.of("time", String.valueOf(seconds)));
                return;
            }
        }
        if (!node.executorConfigured && !node.children.isEmpty()) {
            sendUsage(context, node);
            return;
        }
        long start = System.currentTimeMillis();
        executedHandlers.incrementAndGet();
        try {
            node.executor.execute(context);
        } catch (Throwable error) {
            long logInterval = platformPlugin.getConfig().getLong("command.error.log-interval-ms", platformPlugin.getConfig().getLong("scheduler.task-error-log-interval-ms", 10000L));
            if (shouldEmit(commandErrorWarnings, node.plugin.getName() + ":" + node.path(), logInterval)) {
                node.plugin.getLogger().log(Level.WARNING, "Command /" + context.label() + " path=" + node.path() + " failed", error);
            }
            long feedbackInterval = Math.max(0L, platformPlugin.getConfig().getLong("command.error.cooldown-seconds", 2L) * 1000L);
            if (shouldEmit(commandErrorFeedback, context.sender().getName() + ":" + node.plugin.getName() + ":" + node.path(), feedbackInterval)) {
                sendCommandMessage(context.sender(), node.plugin, "error-message-key", "command.error", Map.of("error", safeError(error)));
            }
        } finally {
            warnSlowCommandHandler(node, context, start);
        }
    }

    private void warnSlowCommandHandler(Builder node, CommandContext context, long start) {
        if (!platformPlugin.getConfig().getBoolean("production-profile.enabled", false)) {
            return;
        }
        long threshold = platformPlugin.getConfig().getLong(
                "production-profile.command.slow-handler-warn-ms",
                platformPlugin.getConfig().getLong("scheduler.slow-task-warn-ms", 250L)
        );
        if (threshold <= 0L) {
            return;
        }
        long took = System.currentTimeMillis() - start;
        if (took < threshold) {
            return;
        }
        slowHandlers.incrementAndGet();
        long interval = platformPlugin.getConfig().getLong("command.diagnostics.slow-handler-log-interval-ms", platformPlugin.getConfig().getLong("scheduler.slow-task-log-interval-ms", 10000L));
        if (shouldEmit(slowHandlerWarnings, node.plugin.getName() + ":" + node.path(), interval)) {
            node.plugin.getLogger().warning("Slow command handler: /" + context.label() + " path=" + node.path() + " took=" + took + "ms threshold=" + threshold + "ms");
        }
    }

    private boolean validateArgs(Builder node, CommandContext context) {
        int count = context.length();
        int min = effectiveMinArgs(node);
        int max = effectiveMaxArgs(node);
        if (count < min || (max >= 0 && count > max)) {
            sendCommandMessage(context.sender(), node.plugin, "invalid-args-message-key", "command.invalid-args", Map.of(
                    "count", String.valueOf(count),
                    "min", String.valueOf(min),
                    "max", max < 0 ? "∞" : String.valueOf(max),
                    "usage", usage(node, context.label())
            ));
            return false;
        }
        return true;
    }

    private void collectRoutes(Builder node, boolean registered) {
        routes.put(routeKey(node), toRoute(node, registered));
        for (Builder child : node.children.values()) {
            collectRoutes(child, registered);
        }
    }

    private CommandRoute toRoute(Builder node, boolean registered) {
        return new CommandRoute(
                node.plugin.getName(),
                root(node).name,
                node.path(),
                node.permission,
                List.copyOf(requiredPermissions(node)),
                usage(node, root(node).name),
                node.usageKey,
                node.description,
                requiresPlayer(node),
                node.executorConfigured,
                legacyDelegate(node),
                legacyDetail(node),
                registered,
                effectiveMinArgs(node),
                effectiveMaxArgs(node),
                node.cooldownSeconds,
                node.rateLimitMaxActions,
                node.rateLimitWindow == null ? 0L : Math.max(0L, node.rateLimitWindow.toMillis()),
                List.copyOf(node.children.keySet())
        );
    }

    private Builder root(Builder node) {
        Builder cursor = node;
        while (cursor.parent != null) {
            cursor = cursor.parent;
        }
        return cursor;
    }

    private void unregisterRoutes(Plugin plugin, String rootName) {
        if (plugin == null || rootName == null) {
            return;
        }
        String prefix = plugin.getName().toLowerCase(Locale.ROOT) + ":" + sanitizeName(rootName) + ":";
        routes.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private String routeKey(Builder node) {
        return node.plugin.getName().toLowerCase(Locale.ROOT) + ":" + root(node).name + ":" + node.path().replace(' ', '/');
    }

    private String rootKey(Plugin plugin, String rootName) {
        return plugin.getName().toLowerCase(Locale.ROOT) + ":" + sanitizeName(rootName);
    }

    private boolean canSee(CommandSender sender, Builder node) {
        for (String permission : requiredPermissions(node)) {
            if (!sender.isOp() && !sender.hasPermission(permission)) {
                return false;
            }
        }
        return true;
    }

    private Set<String> requiredPermissions(Builder node) {
        Set<String> permissions = new LinkedHashSet<>();
        for (Builder current : node.lineage()) {
            if (!current.permission.isBlank()) {
                permissions.add(current.permission);
            }
        }
        return permissions;
    }

    private boolean legacyDelegate(Builder node) {
        for (Builder current : node.lineage()) {
            if (current.legacyDelegate) {
                return true;
            }
        }
        return false;
    }

    private String legacyDetail(Builder node) {
        String detail = "";
        for (Builder current : node.lineage()) {
            if (current.legacyDelegate && !current.legacyDetail.isBlank()) {
                detail = current.legacyDetail;
            }
        }
        return detail;
    }

    private boolean requiresPlayer(Builder node) {
        for (Builder current : node.lineage()) {
            if (current.playerOnly) {
                return true;
            }
        }
        return false;
    }

    private void sendUsage(CommandContext context, Builder node) {
        Map<String, String> placeholders = Map.of(
                "usage", usage(node, context.label()),
                "command", node.path()
        );
        if (!node.usageKey.isBlank()) {
            languageService.send(context.sender(), node.plugin, node.usageKey, placeholders);
            return;
        }
        sendCommandMessage(context.sender(), node.plugin, "usage-message-key", "command.usage", placeholders);
    }

    private String usage(Builder node, String label) {
        if (!node.usage.isBlank()) {
            return node.usage;
        }
        String base = "/" + label;
        List<Builder> lineage = node.lineage();
        if (lineage.size() > 1) {
            base += " " + String.join(" ", lineage.stream().skip(1).map(builder -> builder.name).toList());
        }
        if (!argumentSpecs(node).isEmpty()) {
            return base + " " + argumentUsage(node);
        }
        if (!node.children.isEmpty()) {
            return base + " <" + String.join("|", node.children.keySet()) + ">";
        }
        return base;
    }


    private String argumentUsage(Builder node) {
        List<String> parts = new ArrayList<>();
        List<CommandArgument> arguments = argumentSpecs(node);
        for (int i = 0; i < arguments.size(); i++) {
            CommandArgument argument = arguments.get(i);
            String name = argument.name();
            boolean required = i < effectiveMinArgs(node);
            if (argument.type() == CommandArgumentType.GREEDY_STRING) {
                name = name + "...";
            }
            parts.add(required ? "<" + name + ">" : "[" + name + "]");
        }
        return String.join(" ", parts);
    }

    private void sendCommandMessage(CommandSender sender, Plugin plugin, String configKey, String defaultKey, Map<String, String> placeholders) {
        String key = defaultKey;
        if (platformPlugin.getConfig().isString("command.defaults." + configKey)) {
            key = platformPlugin.getConfig().getString("command.defaults." + configKey, defaultKey);
        }
        if (key == null || key.isBlank()) {
            key = defaultKey;
        }
        languageService.send(sender, plugin, key, placeholders);
    }


    private boolean shouldEmit(Map<String, Long> guard, String key, long intervalMillis) {
        if (intervalMillis <= 0L) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long previous = guard.putIfAbsent(key, now);
        if (previous == null) {
            return true;
        }
        if (now - previous >= intervalMillis) {
            guard.put(key, now);
            return true;
        }
        return false;
    }

    private int maxSuggestions() {
        return Math.max(16, Math.min(512, platformPlugin.getConfig().getInt("command.paper-native.max-suggestions", 128)));
    }

    private boolean paperNativeRequirements() {
        return platformPlugin.getConfig().getBoolean("command.paper-native.permission-requirements", true);
    }

    private boolean inferUsageArguments() {
        return platformPlugin.getConfig().getBoolean("command.paper-native.infer-usage-arguments", true);
    }

    private Collection<String> aliases(Builder root) {
        Set<String> aliases = new LinkedHashSet<>(root.aliases);
        PluginCommand command = root.plugin.getServer().getPluginCommand(root.name);
        if (command != null && command.getPlugin().equals(root.plugin)) {
            aliases.addAll(command.getAliases());
        }
        Map<String, Map<String, Object>> commands = root.plugin.getDescription().getCommands();
        if (commands != null) {
            Map<String, Object> descriptor = commands.get(root.name);
            if (descriptor != null) {
                Object configured = descriptor.get("aliases");
                if (configured instanceof Iterable<?> iterable) {
                    for (Object value : iterable) {
                        if (value != null) {
                            aliases.add(String.valueOf(value));
                        }
                    }
                } else if (configured instanceof String value) {
                    aliases.add(value);
                }
            }
        }
        aliases.removeIf(alias -> alias == null || alias.isBlank() || sanitizeName(alias).equals(root.name));
        return List.copyOf(aliases);
    }

    private String label(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, Builder node) {
        String input = context.getInput();
        if (input != null && !input.isBlank()) {
            String trimmed = input.charAt(0) == '/' ? input.substring(1).trim() : input.trim();
            int split = firstWhitespace(trimmed);
            String label = split < 0 ? trimmed : trimmed.substring(0, split);
            if (!label.isBlank()) {
                return label;
            }
        }
        return root(node).name;
    }

    private String[] splitForExecution(String raw) {
        if (raw == null || raw.trim().isBlank()) {
            return new String[0];
        }
        return raw.trim().split("\\s+");
    }

    private String[] splitForCompletion(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new String[]{""};
        }
        boolean trailingWhitespace = Character.isWhitespace(raw.charAt(raw.length() - 1));
        String trimmed = raw.trim();
        List<String> result = new ArrayList<>();
        if (!trimmed.isEmpty()) {
            result.addAll(Arrays.asList(trimmed.split("\\s+")));
        }
        if (trailingWhitespace) {
            result.add("");
        }
        return result.toArray(String[]::new);
    }

    private String prefixBeforeCurrentToken(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        int index = lastWhitespace(raw);
        return index < 0 ? "" : raw.substring(0, index + 1);
    }

    private String currentToken(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        int index = lastWhitespace(raw);
        return index < 0 ? raw : raw.substring(index + 1);
    }

    private int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private int lastWhitespace(String value) {
        for (int i = value.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }

    private UUID subject(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId();
        }
        return UUID.nameUUIDFromBytes(("command-sender:" + sender.getName()).getBytes(StandardCharsets.UTF_8));
    }

    private String sanitizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private String safeError(Throwable error) {
        String message = error == null ? "unknown" : error.getMessage();
        if (message == null || message.isBlank()) {
            return error == null ? "unknown" : error.getClass().getSimpleName();
        }
        return message.replaceAll("(?i)(password|token|secret|apikey|api-key)=([^,\\s]+)", "$1=***");
    }

    private record ResolvedCommand(Builder node, String[] remainingArgs, String unknownArgument) {
    }
}
