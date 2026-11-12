package vn.ledat.platform.core.gui;

import org.bukkit.plugin.Plugin;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import vn.ledat.platform.api.gui.GuiLayout;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;

class GuiLayoutCompatibilityTest {
    @Test
    void legacyPatternsRemainPermissiveWhileCompiledLayoutsHaveTheExactCapacity() {
        try (var itemConstruction = mockConstruction(ItemStack.class)) {
        var legacy = new SimplePlatformGui.Builder(mock(Plugin.class), "legacy").fill(null).pattern("?").build();
        assertEquals(9, legacy.size());
        var layout = GuiLayout.compile("menu", List.of("IIIIIIIII", "IIIIIIIII"),
                Map.of('I', new GuiLayout.Symbol("info", GuiLayout.Kind.STATIC)));
        var compiled = new SimplePlatformGui.Builder(mock(Plugin.class), "menu").fill(null).layout(layout).build();
        assertEquals(18, compiled.size());
        }
    }
}
