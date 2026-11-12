package vn.ledat.platform.core.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import vn.ledat.platform.api.gui.GuiService;
import vn.ledat.platform.api.language.LanguageService;
import vn.ledat.platform.api.scheduler.LeDatScheduler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class DefaultGuiTitleTest {
    enum TitleSource { COMPONENT, LITERAL, LANGUAGE }

    @ParameterizedTest
    @EnumSource(TitleSource.class)
    void actualOpenedChestUsesItsNormalizedCapacityForEveryTitleSource(TitleSource source) {
        Plugin owner = mock(Plugin.class);
        Player player = mock(Player.class);
        PluginManager manager = mock(PluginManager.class);
        Inventory inventory = mock(Inventory.class);
        LanguageService language = mock(LanguageService.class);
        LeDatScheduler scheduler = mock(LeDatScheduler.class);
        GuiService.PlatformGui gui = mock(GuiService.PlatformGui.class);
        AtomicReference<Component> openedTitle = new AtomicReference<>();
        when(owner.getConfig()).thenReturn(new YamlConfiguration());
        when(owner.getName()).thenReturn("TitleTest");
        when(owner.getLogger()).thenReturn(Logger.getLogger("DefaultGuiTitleTest"));
        when(owner.isEnabled()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(scheduler.isOwnedByCurrentRegion(player)).thenReturn(true);
        when(gui.plugin()).thenReturn(owner);
        when(gui.menuId()).thenReturn("shop");
        when(gui.size()).thenReturn(35);
        when(gui.context()).thenReturn(Map.of());
        when(gui.staticItems()).thenReturn(Map.of());
        when(gui.buttons()).thenReturn(Map.of());
        String marker = "<glyph:row_6> Shop {rows}";
        switch (source) {
            case COMPONENT -> when(gui.title()).thenReturn(Component.text(marker));
            case LITERAL -> when(gui.titleKey()).thenReturn("literal:" + marker);
            case LANGUAGE -> {
                when(gui.titleKey()).thenReturn("shop.title");
                when(language.resolveLocale(player)).thenReturn("vi_VN");
                when(language.component(owner, "vi_VN", "shop.title", Map.of("page", "1", "rows", "3")))
                        .thenReturn(Component.text("<glyph:row_6> Shop 3"));
            }
        }
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27), any(Component.class)))
                    .thenAnswer(invocation -> {
                        openedTitle.set(invocation.getArgument(2));
                        return inventory;
                    });
            new DefaultGuiService(owner, language, scheduler, null).open(player, gui);
        }
        assertEquals("<glyph:row_3> Shop 3", PlainTextComponentSerializer.plainText().serialize(openedTitle.get()));
        assertEquals(35, gui.size());
    }
}
