package vn.ledat.platform.core.gui;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryTitleNormalizerTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @ParameterizedTest
    @CsvSource({"9,1", "18,2", "27,3", "36,4", "45,5", "54,6", "0,1", "17,1", "35,3", "100,6"})
    void usesFinalNormalizedChestCapacityBeforeParsing(int slots, int rows) {
        String raw = InventoryTitleNormalizer.normalize("<shift:-12><glyph:row_{rows}><white>Shop {rows}", slots);
        assertEquals("<shift:-12><glyph:row_" + rows + "><white>Shop " + rows, raw);
        Component parsed = MiniMessage.miniMessage().deserialize(raw);
        assertEquals("<shift:-12><glyph:row_" + rows + ">Shop " + rows, PLAIN.serialize(parsed));
        assertEquals("<shift:-12><glyph:row" + rows + ">Shop " + rows,
                PLAIN.serialize(MiniMessage.miniMessage().deserialize(
                        InventoryTitleNormalizer.normalize("<shift:-12><glyph:row{rows}><white>Shop {rows}", slots))));
    }

    @Test
    void rewritesOnlyKnownExistingRowGlyphsWithoutActivatingABackground() {
        assertEquals("Plain shop", InventoryTitleNormalizer.normalize("Plain shop", 36));
        assertEquals("<glyph:row_4><glyph:row_4><glyph:row4><glyph:row_7><glyph:custom>",
                InventoryTitleNormalizer.normalize("<glyph:row_1><glyph:row_6><glyph:row6><glyph:row_7><glyph:custom>", 36));
    }

    @Test
    void componentReplacementRetainsAuthoredFontStyleAndHover() {
        Component title = Component.text("<glyph:row_6><glyph:row6> {rows}", NamedTextColor.WHITE)
                .font(Key.key("minecraft:menu"))
                .hoverEvent(Component.text("metadata"));
        Component normalized = InventoryTitleNormalizer.normalize(title, 27);
        assertEquals("<glyph:row_3><glyph:row3> 3", PLAIN.serialize(normalized));
        assertEquals(title.font(), normalized.font());
        assertEquals(title.color(), normalized.color());
        assertEquals(title.hoverEvent(), normalized.hoverEvent());
    }
}
