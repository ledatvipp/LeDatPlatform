package vn.ledat.platform.api.command;

import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public interface CommandService {
    CommandBuilder command(Plugin plugin, String name);

    /**
     * Runtime route registry for commands registered through the platform command service.
     * The map key is stable and implementation-defined, usually plugin:root:path.
     */
    default Map<String, CommandRoute> routes() {
        return Map.of();
    }

    /**
     * Aggregated command diagnostics. Implementations may return zero stats when unsupported.
     */
    default CommandStats stats() {
        return new CommandStats(0, 0, 0, 0, 0, 0, 0, 0, Instant.now());
    }

    /**
     * Remove runtime metadata for a plugin. This does not unregister Bukkit plugin.yml commands.
     */
    default void unregisterPlugin(Plugin plugin) {
    }

    interface CommandBuilder {
        CommandBuilder permission(String permission);
        CommandBuilder usage(String usage);

        /**
         * Optional language key used when showing usage. When empty, command.usage is used with {usage}.
         */
        default CommandBuilder usageKey(String key) {
            return this;
        }

        CommandBuilder description(String description);
        CommandBuilder playerOnly(boolean playerOnly);
        CommandBuilder cooldownSeconds(long seconds);

        /** Programmatic aliases in addition to aliases declared in plugin.yml. */
        default CommandBuilder aliases(String... aliases) {
            return this;
        }

        /**
         * Provide completions for one typed argument without replacing the node's
         * general tab provider. The context args include the current token.
         */
        default CommandBuilder suggestions(String argumentName, CommandTabProvider provider) {
            return this;
        }

        /**
         * Mark this command node as a legacy command delegate.
         * This is metadata-only and does not change behavior; it helps /ledatplatform doctor commands
         * show which commands still need full CommandService migration.
         */
        default CommandBuilder legacyDelegate(boolean legacy) {
            return this;
        }

        /**
         * Mark this command node as a legacy delegate with a human-readable migration detail.
         */
        default CommandBuilder legacyDelegate(String detail) {
            return legacyDelegate(true);
        }

        /**
         * Require at least this many remaining arguments after subcommand resolution.
         */
        default CommandBuilder minArgs(int minArgs) {
            return this;
        }

        /**
         * Require at most this many remaining arguments after subcommand resolution. -1 means unlimited.
         */
        default CommandBuilder maxArgs(int maxArgs) {
            return this;
        }

        default CommandBuilder exactArgs(int count) {
            return minArgs(count).maxArgs(count);
        }

        /**
         * Per-sender rate-limit in addition to cooldown. Useful for commands that can be spammed with different args.
         */
        default CommandBuilder rateLimit(int maxActions, Duration window) {
            return this;
        }
        /**
         * Add one typed argument to this command node. This metadata is mapped to
         * Paper Brigadier by the platform while keeping CommandContext args stable.
         */
        default CommandBuilder argument(CommandArgument argument) {
            return this;
        }

        default CommandBuilder argument(String name, CommandArgumentType type) {
            return argument(CommandArgument.of(name, type));
        }

        default CommandBuilder choicesArgument(String name, List<String> choices) {
            return argument(new CommandArgument(name, CommandArgumentType.ENUM, choices, CommandArgument.NO_MIN, CommandArgument.NO_MAX));
        }

        default CommandBuilder choicesArgument(String name, String... choices) {
            return choicesArgument(name, choices == null ? List.of() : Arrays.asList(choices));
        }
        default CommandBuilder optionalArgument(String name, CommandArgumentType type) {
            return argument(CommandArgument.optional(name, type));
        }

        default CommandBuilder optionalChoicesArgument(String name, List<String> choices) {
            return argument(new CommandArgument(name, CommandArgumentType.ENUM, choices, CommandArgument.NO_MIN, CommandArgument.NO_MAX, false));
        }

        default CommandBuilder optionalChoicesArgument(String name, String... choices) {
            return optionalChoicesArgument(name, choices == null ? List.of() : Arrays.asList(choices));
        }

        default CommandBuilder playerArgument(String name) {
            return argument(name, CommandArgumentType.PLAYER_NAME);
        }

        default CommandBuilder optionalPlayerArgument(String name) {
            return optionalArgument(name, CommandArgumentType.PLAYER_NAME);
        }

        default CommandBuilder offlinePlayerArgument(String name) {
            return argument(name, CommandArgumentType.OFFLINE_PLAYER_NAME);
        }

        default CommandBuilder optionalOfflinePlayerArgument(String name) {
            return optionalArgument(name, CommandArgumentType.OFFLINE_PLAYER_NAME);
        }

        default CommandBuilder materialArgument(String name) {
            return argument(name, CommandArgumentType.MATERIAL_NAME);
        }

        default CommandBuilder customItemArgument(String name) {
            return argument(name, CommandArgumentType.CUSTOM_ITEM_KEY);
        }

        default CommandBuilder worldArgument(String name) {
            return argument(name, CommandArgumentType.WORLD_NAME);
        }

        default CommandBuilder optionalWorldArgument(String name) {
            return optionalArgument(name, CommandArgumentType.WORLD_NAME);
        }

        default CommandBuilder serverArgument(String name) {
            return argument(name, CommandArgumentType.SERVER_NAME);
        }

        default CommandBuilder optionalServerArgument(String name) {
            return optionalArgument(name, CommandArgumentType.SERVER_NAME);
        }

        default CommandBuilder permissionArgument(String name) {
            return argument(name, CommandArgumentType.PERMISSION);
        }

        default CommandBuilder optionalPermissionArgument(String name) {
            return optionalArgument(name, CommandArgumentType.PERMISSION);
        }

        default CommandBuilder uuidArgument(String name) {
            return argument(name, CommandArgumentType.UUID);
        }

        default CommandBuilder optionalUuidArgument(String name) {
            return optionalArgument(name, CommandArgumentType.UUID);
        }

        default CommandBuilder durationArgument(String name) {
            return argument(name, CommandArgumentType.DURATION);
        }

        default CommandBuilder optionalDurationArgument(String name) {
            return optionalArgument(name, CommandArgumentType.DURATION);
        }

        default CommandBuilder languageArgument(String name) {
            return argument(name, CommandArgumentType.LANGUAGE);
        }

        default CommandBuilder optionalLanguageArgument(String name) {
            return optionalArgument(name, CommandArgumentType.LANGUAGE);
        }

        default CommandBuilder booleanArgument(String name) {
            return argument(name, CommandArgumentType.BOOLEAN);
        }

        default CommandBuilder wordArgument(String name) {
            return argument(name, CommandArgumentType.WORD);
        }

        default CommandBuilder optionalWordArgument(String name) {
            return optionalArgument(name, CommandArgumentType.WORD);
        }

        default CommandBuilder stringArgument(String name) {
            return argument(name, CommandArgumentType.STRING);
        }

        default CommandBuilder optionalStringArgument(String name) {
            return optionalArgument(name, CommandArgumentType.STRING);
        }

        default CommandBuilder greedyStringArgument(String name) {
            return argument(name, CommandArgumentType.GREEDY_STRING);
        }

        default CommandBuilder optionalGreedyStringArgument(String name) {
            return optionalArgument(name, CommandArgumentType.GREEDY_STRING);
        }

        default CommandBuilder integerArgument(String name, int min, int max) {
            return argument(new CommandArgument(name, CommandArgumentType.INTEGER, List.of(), min, max));
        }

        default CommandBuilder optionalIntegerArgument(String name, int min, int max) {
            return argument(new CommandArgument(name, CommandArgumentType.INTEGER, List.of(), min, max, false));
        }

        default CommandBuilder moneyArgument(String name, double min, double max) {
            return argument(new CommandArgument(name, CommandArgumentType.MONEY, List.of(), CommandArgument.NO_MIN, CommandArgument.NO_MAX, null, null, min, max, true));
        }

        default CommandBuilder optionalMoneyArgument(String name, double min, double max) {
            return argument(new CommandArgument(name, CommandArgumentType.MONEY, List.of(), CommandArgument.NO_MIN, CommandArgument.NO_MAX, null, null, min, max, false));
        }

        default CommandBuilder doubleArgument(String name, double min, double max) {
            return argument(CommandArgument.rangedDouble(name, min, max));
        }

        default CommandBuilder optionalDoubleArgument(String name, double min, double max) {
            return argument(CommandArgument.optionalRangedDouble(name, min, max));
        }

        default CommandBuilder longArgument(String name, long min, long max) {
            return argument(CommandArgument.rangedLong(name, min, max));
        }

        default CommandBuilder optionalLongArgument(String name, long min, long max) {
            return argument(CommandArgument.optionalRangedLong(name, min, max));
        }

        default <E extends Enum<E>> CommandBuilder enumArgument(String name, Class<E> enumType) {
            return choicesArgument(name, enumChoices(enumType));
        }

        default <E extends Enum<E>> CommandBuilder optionalEnumArgument(String name, Class<E> enumType) {
            return optionalChoicesArgument(name, enumChoices(enumType));
        }

        private static <E extends Enum<E>> List<String> enumChoices(Class<E> enumType) {
            if (enumType == null) {
                return List.of();
            }
            E[] constants = enumType.getEnumConstants();
            if (constants == null) {
                return List.of();
            }
            return Arrays.stream(constants)
                    .map(value -> value.name().toLowerCase(Locale.ROOT))
                    .toList();
        }

        CommandBuilder executor(CommandExecutor executor);
        CommandBuilder tab(CommandTabProvider provider);
        CommandBuilder subCommand(String name, Consumer<CommandBuilder> configurer);
        void register();
    }
}
