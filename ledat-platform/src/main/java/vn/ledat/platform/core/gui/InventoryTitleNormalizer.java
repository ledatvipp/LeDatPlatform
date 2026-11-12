package vn.ledat.platform.core.gui;

import net.kyori.adventure.text.Component;

import java.util.regex.Pattern;

/** Substitutes existing chest header row markers without resolving resource-pack glyphs. */
public final class InventoryTitleNormalizer {
    private static final Pattern ROW_GLYPH = Pattern.compile("(<glyph:row_?)[1-6]>");
    private static final Pattern ROW_MARKER = Pattern.compile("\\{rows}|(<glyph:row_?)[1-6]>");

    private InventoryTitleNormalizer() {
    }

    public static int normalizeSize(int size) {
        int clamped = Math.max(9, Math.min(54, size));
        return clamped - clamped % 9;
    }

    /** Call before MiniMessage parsing so row placeholders can occur inside glyph tag arguments. */
    public static String normalize(String title, int size) {
        String rows = String.valueOf(normalizeSize(size) / 9);
        String resolved = (title == null ? "" : title).replace("{rows}", rows);
        return ROW_GLYPH.matcher(resolved).replaceAll(match -> match.group(1) + rows + ">");
    }

    /** Keeps authored font, style and object-component metadata on explicit Component headers. */
    public static Component normalize(Component title, int size) {
        if (title == null) {
            return Component.empty();
        }
        String rows = String.valueOf(normalizeSize(size) / 9);
        return title.replaceText(builder -> builder.match(ROW_MARKER)
                .replacement((match, text) -> text.content(match.group(1) == null
                        ? rows : match.group(1) + rows + ">")));
    }
}
