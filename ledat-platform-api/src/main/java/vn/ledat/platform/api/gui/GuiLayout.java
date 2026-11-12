package vn.ledat.platform.api.gui;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** An opt-in, immutable 9-column layout. Existing permissive builder patterns remain unchanged. */
public final class GuiLayout {
    public enum Kind { DECORATION, STATIC, BUTTON, COLLECTION, CONDITIONAL, EMPTY }

    public record Symbol(String id, Kind kind) {
        public Symbol {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Layout symbol id is required");
            Objects.requireNonNull(kind, "kind");
        }
    }

    private final String menuId;
    private final List<String> rows;
    private final Map<Character, Symbol> symbols;
    private final Map<Character, List<Integer>> slots;

    private GuiLayout(String menuId, List<String> rows, Map<Character, Symbol> symbols,
                      Map<Character, List<Integer>> slots) {
        this.menuId = menuId;
        this.rows = List.copyOf(rows);
        this.symbols = Collections.unmodifiableMap(new LinkedHashMap<>(symbols));
        Map<Character, List<Integer>> immutable = new LinkedHashMap<>();
        slots.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        this.slots = Collections.unmodifiableMap(immutable);
    }

    public static GuiLayout compile(String menuId, List<String> rows, Map<Character, Symbol> symbols) {
        return compile("<programmatic>", menuId, rows, symbols);
    }

    public static GuiLayout compile(String source, String menuId, List<String> rows, Map<Character, Symbol> symbols) {
        if (menuId == null || menuId.isBlank()) throw invalid(source, menuId, "menu-id", -1, -1, "Menu id is required");
        if (rows == null || rows.isEmpty() || rows.size() > 6) {
            throw invalid(source, menuId, "layout.rows", -1, -1, "Expected 1 to 6 rows");
        }
        if (symbols == null) throw invalid(source, menuId, "layout.symbols", -1, -1, "Symbols are required");
        Map<Character, Symbol> declared = new LinkedHashMap<>(symbols);
        Map<String, Character> ids = new LinkedHashMap<>();
        Map<Character, List<Integer>> slots = new LinkedHashMap<>();
        for (var entry : declared.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw invalid(source, menuId, "layout.symbols", -1, -1, "Null symbol declaration");
            }
            Character previous = ids.putIfAbsent(entry.getValue().id(), entry.getKey());
            if (previous != null) {
                throw invalid(source, menuId, "layout.symbols", -1, -1, "Component id " + entry.getValue().id() + " maps to multiple symbols");
            }
            slots.put(entry.getKey(), new ArrayList<>());
        }
        for (int row = 0; row < rows.size(); row++) {
            String line = rows.get(row);
            if (line == null || line.length() != 9) {
                throw invalid(source, menuId, "layout.rows", row, -1, "Each row must contain exactly 9 characters");
            }
            for (int column = 0; column < 9; column++) {
                char symbol = line.charAt(column);
                if (!declared.containsKey(symbol)) {
                    throw invalid(source, menuId, "layout.rows", row, column, "Undeclared symbol '" + symbol + "'");
                }
                slots.get(symbol).add(row * 9 + column);
            }
        }
        for (var entry : declared.entrySet()) {
            if (entry.getValue().kind() == Kind.COLLECTION && slots.get(entry.getKey()).isEmpty()) {
                throw invalid(source, menuId, "layout.symbols." + entry.getKey(), -1, -1, "Collection region is empty");
            }
        }
        return new GuiLayout(menuId, rows, declared, slots);
    }

    /** Reads menu-id, layout.rows and layout.symbols; symbol definitions contain id and type. */
    public static GuiLayout from(ConfigurationSection menu) {
        Objects.requireNonNull(menu, "menu");
        String menuId = menu.getString("menu-id", menu.getName());
        String source = menu.getCurrentPath().isBlank() ? "<yaml>" : menu.getCurrentPath();
        ConfigurationSection section = menu.getConfigurationSection("layout.symbols");
        if (section == null) throw invalid(source, menuId, "layout.symbols", -1, -1, "Symbols are required");
        Map<Character, Symbol> symbols = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            if (key.length() != 1) throw invalid(source, menuId, "layout.symbols." + key, -1, -1, "Symbol keys must contain one character");
            ConfigurationSection definition = section.getConfigurationSection(key);
            if (definition == null) throw invalid(source, menuId, "layout.symbols." + key, -1, -1, "Expected id and type");
            try {
                symbols.put(key.charAt(0), new Symbol(definition.getString("id"),
                        Kind.valueOf(definition.getString("type", "STATIC").toUpperCase(Locale.ROOT))));
            } catch (IllegalArgumentException error) {
                throw invalid(source, menuId, "layout.symbols." + key, -1, -1, error.getMessage());
            }
        }
        return compile(source, menuId, menu.getStringList("layout.rows"), symbols);
    }

    public String menuId() { return menuId; }
    public int size() { return rows.size() * 9; }
    public List<String> rows() { return rows; }
    public Map<Character, Symbol> symbols() { return symbols; }
    public List<Integer> slots(char symbol) { return slots.getOrDefault(symbol, List.of()); }
    public List<Integer> slots(String componentId) {
        return symbols.entrySet().stream().filter(entry -> entry.getValue().id().equals(componentId))
                .findFirst().map(entry -> slots(entry.getKey())).orElse(List.of());
    }

    private static IllegalArgumentException invalid(String source, String menuId, String path, int row, int column, String reason) {
        return new IllegalArgumentException(source + ": menu=" + menuId + " path=" + path
                + (row >= 0 ? " row=" + row : "") + (column >= 0 ? " column=" + column : "") + ": " + reason);
    }
}
