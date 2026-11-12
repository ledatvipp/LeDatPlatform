package vn.ledat.platform.api.command;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Lightweight typed-argument metadata for Paper Brigadier command registration.
 *
 * <p>Runtime validation still supports {@link CommandService.CommandBuilder#minArgs(int)}
 * and {@link CommandService.CommandBuilder#maxArgs(int)}. When min/max are not
 * configured, the platform can derive safe bounds from this metadata.</p>
 */
public record CommandArgument(
        String name,
        CommandArgumentType type,
        List<String> suggestions,
        int min,
        int max,
        Long longMin,
        Long longMax,
        Double doubleMin,
        Double doubleMax,
        boolean required
) {
    public static final int NO_MIN = Integer.MIN_VALUE;
    public static final int NO_MAX = Integer.MAX_VALUE;

    public CommandArgument(String name, CommandArgumentType type, List<String> suggestions, int min, int max) {
        this(name, type, suggestions, min, max, null, null, null, null, true);
    }

    public CommandArgument(String name, CommandArgumentType type, List<String> suggestions, int min, int max, boolean required) {
        this(name, type, suggestions, min, max, null, null, null, null, required);
    }

    public CommandArgument {
        name = sanitizeName(name);
        type = type == null ? CommandArgumentType.WORD : type;
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList());
        if (name.isBlank()) {
            throw new IllegalArgumentException("command argument name cannot be blank");
        }
    }

    public static CommandArgument of(String name, CommandArgumentType type) {
        return new CommandArgument(name, type, List.of(), NO_MIN, NO_MAX, true);
    }

    public static CommandArgument optional(String name, CommandArgumentType type) {
        return new CommandArgument(name, type, List.of(), NO_MIN, NO_MAX, false);
    }

    public static CommandArgument choices(String name, String... values) {
        return new CommandArgument(name, CommandArgumentType.ENUM, values == null ? List.of() : Arrays.asList(values), NO_MIN, NO_MAX, true);
    }

    public static CommandArgument optionalChoices(String name, String... values) {
        return new CommandArgument(name, CommandArgumentType.ENUM, values == null ? List.of() : Arrays.asList(values), NO_MIN, NO_MAX, false);
    }

    public static CommandArgument rangedInt(String name, int min, int max) {
        return new CommandArgument(name, CommandArgumentType.INTEGER, List.of(), min, max, true);
    }

    public static CommandArgument rangedLong(String name, long min, long max) {
        return new CommandArgument(name, CommandArgumentType.LONG, List.of(), NO_MIN, NO_MAX, min, max, null, null, true);
    }

    public static CommandArgument optionalRangedLong(String name, long min, long max) {
        return new CommandArgument(name, CommandArgumentType.LONG, List.of(), NO_MIN, NO_MAX, min, max, null, null, false);
    }

    public static CommandArgument rangedDouble(String name, double min, double max) {
        return new CommandArgument(name, CommandArgumentType.DOUBLE, List.of(), NO_MIN, NO_MAX, null, null, min, max, true);
    }

    public static CommandArgument optionalRangedDouble(String name, double min, double max) {
        return new CommandArgument(name, CommandArgumentType.DOUBLE, List.of(), NO_MIN, NO_MAX, null, null, min, max, false);
    }

    public boolean hasRange() {
        return min != NO_MIN || max != NO_MAX || longMin != null || longMax != null || doubleMin != null || doubleMax != null;
    }

    public long effectiveLongMin() {
        return longMin == null ? (min == NO_MIN ? Long.MIN_VALUE : min) : longMin;
    }

    public long effectiveLongMax() {
        return longMax == null ? (max == NO_MAX ? Long.MAX_VALUE : max) : longMax;
    }

    public double effectiveDoubleMin() {
        return doubleMin == null ? (min == NO_MIN ? -Double.MAX_VALUE : min) : doubleMin;
    }

    public double effectiveDoubleMax() {
        return doubleMax == null ? (max == NO_MAX ? Double.MAX_VALUE : max) : doubleMax;
    }

    private static String sanitizeName(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        String trimmed = value.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                builder.append(c);
            } else if (Character.isWhitespace(c)) {
                builder.append('_');
            }
        }
        return builder.toString();
    }
}
