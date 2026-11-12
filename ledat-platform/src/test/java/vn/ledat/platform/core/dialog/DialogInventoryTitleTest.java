package vn.ledat.platform.core.dialog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import vn.ledat.platform.api.dialog.PlatformDialog;
import vn.ledat.platform.api.gui.GuiService;
import vn.ledat.platform.api.language.LanguageService;
import vn.ledat.platform.api.scheduler.LeDatScheduler;
import vn.ledat.platform.core.bedrockform.BedrockTextRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DialogInventoryTitleTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void blankDefaultFillRemainsNonNullAndExplicitFillIsPreserved() {
        try (MockedConstruction<ItemStack> stacks = mockConstruction(ItemStack.class, (item, context) -> {
            when(item.getType()).thenReturn((Material) context.arguments().get(0));
            when(item.clone()).thenReturn(item);
        })) {
            PlatformDialog blank = PlatformDialog.builder(null, "blank").build();
            assertEquals(Material.AIR, blank.fallbackFillItem().getType());
            ItemStack explicit = mock(ItemStack.class);
            when(explicit.clone()).thenReturn(explicit);
            when(explicit.getType()).thenReturn(Material.BLACK_STAINED_GLASS_PANE);
            PlatformDialog filled = PlatformDialog.builder(null, "filled").fallbackFillItem(explicit).build();
            assertSame(explicit, filled.fallbackFillItem());
            assertEquals(Material.BLACK_STAINED_GLASS_PANE, filled.fallbackFillItem().getType());
        }
    }

    @Test
    void oldCallersRetainTheSemanticTitleAndSizeContract() {
        Component semantic = Component.text("Confirm purchase");
        PlatformDialog dialog = builder().title(semantic).fallbackSize(35).build();
        assertSame(semantic, dialog.title());
        assertSame(semantic, dialog.inventoryTitle());
        assertEquals(27, dialog.fallbackSize());
    }

    @Test
    void explicitInventoryTitleLeavesSemanticTitleUntouched() {
        Component semantic = Component.text("Confirm purchase");
        Component inventory = Component.text("<glyph:row_6> Shop");
        PlatformDialog dialog = builder().title(semantic).inventoryTitle(inventory).build();
        assertSame(semantic, dialog.title());
        assertSame(inventory, dialog.inventoryTitle());
        assertSame(semantic, builder().title(semantic).inventoryTitle(null).build().inventoryTitle());
    }

    @Test
    void defaultInfoItemKeepsTheSemanticTitle() {
        Component semantic = Component.text("Confirm purchase");
        try (MockedConstruction<ItemStack> stacks = mockConstruction(ItemStack.class, (item, context) -> {
            when(item.getItemMeta()).thenReturn(mock(ItemMeta.class));
            when(item.clone()).thenReturn(item);
        })) {
            PlatformDialog dialog = PlatformDialog.builder(null, "confirm")
                    .title(semantic).inventoryTitle(Component.text("<glyph:row_3>Shop")).build();
            assertEquals(2, stacks.constructed().size());
            ItemMeta infoMeta = dialog.fallbackInfoItem().getItemMeta();
            verify(infoMeta).displayName(Component.text("").append(semantic)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
    }

    @Test
    void bedrockFallbackAlwaysProjectsTheSemanticTitle() throws Exception {
        Component semantic = Component.text("Confirm purchase");
        Component inventory = Component.text("<glyph:row_6> Shop");
        PlatformDialog dialog = builder().title(semantic).inventoryTitle(inventory).build();
        BedrockTextRenderer renderer = mock(BedrockTextRenderer.class);
        when(renderer.render(semantic)).thenReturn("Confirm purchase");
        YamlConfiguration config = overrideConfig();

        assertEquals("Confirm purchase", PLAIN.serialize(DefaultDialogService.fallbackTitle(dialog, true, renderer, config, "TitleTest")));
        verify(renderer).render(semantic);
        verify(renderer, never()).render(inventory);
        assertSame(inventory, DefaultDialogService.fallbackTitle(dialog, false, renderer, new YamlConfiguration(), "TitleTest"));
    }

    @Test
    void providerOverrideIsExactScopedAndUsesActualRows() throws Exception {
        YamlConfiguration config = overrideConfig();
        PlatformDialog dialog = builder().title(Component.text("Confirm purchase")).fallbackSize(35).build();
        Component title = DefaultDialogService.fallbackTitle(dialog, false, mock(BedrockTextRenderer.class), config, "TITLEtest");
        assertEquals("<shift:-12><glyph:row_3>Confirm purchase", PLAIN.serialize(title));
        assertNull(DefaultDialogService.inventoryTitleOverride(config, "OtherPlugin", "confirm.v2"));
        assertNull(DefaultDialogService.inventoryTitleOverride(config, "TitleTest", "other"));
        assertNull(DefaultDialogService.inventoryTitleOverride(config, "TitleTest", "CONFIRM.V2"));
        assertEquals("<shift:-12><glyph:row_{rows}>{title}",
                DefaultDialogService.inventoryTitleOverride(config, "titleTEST", "confirm.v2"));
    }

    @Test
    void validTemplateRowsTakePrecedenceOverLegacySlots() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("fallback.size", 54);
        for (int rows = 1; rows <= 6; rows++) {
            yaml.set("fallback.rows", rows);
            assertEquals(rows * 9, DefaultDialogService.templateFallbackSize(yaml, 27, message -> { throw new AssertionError(message); }));
        }
    }

    @Test
    void invalidTemplateRowsKeepNormalizedLegacyCapacityAndWarn() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("fallback.size", 35);
        List<String> warnings = new ArrayList<>();
        for (Object rows : List.of(0, 7, -1, "4", 4.5)) {
            yaml.set("fallback.rows", rows);
            assertEquals(27, DefaultDialogService.templateFallbackSize(yaml, 54, warnings::add));
        }
        assertEquals(5, warnings.size());
        yaml.set("fallback.rows", null);
        assertEquals(27, DefaultDialogService.templateFallbackSize(yaml, 54, warnings::add));
        assertEquals(5, warnings.size());
        yaml.set("fallback.size", null);
        assertEquals(54, DefaultDialogService.templateFallbackSize(yaml, 54, warnings::add));
    }

    @Test
    void templateSeparatesTitlesAndSubstitutesRowsBeforeMarkup(@TempDir Path temp) throws Exception {
        Files.createDirectories(temp.resolve("dialogs"));
        Files.writeString(temp.resolve("dialogs/confirm.yml"), """
                title: '<red>Legacy title'
                dialog-title: '<aqua>Confirm purchase'
                inventory-title: '<shift:-12><glyph:row_{rows}><white>Shop {rows}'
                fallback:
                  rows: 4
                  size: 54
                """);
        Plugin owner = mock(Plugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("dialogs.templates.auto-save-bundled", false);
        when(owner.getConfig()).thenReturn(config);
        when(owner.getName()).thenReturn("TitleTest");
        when(owner.getDataFolder()).thenReturn(temp.toFile());
        when(owner.getLogger()).thenReturn(Logger.getLogger("DialogInventoryTitleTest"));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<ItemStack> stacks = mockConstruction(ItemStack.class, (item, context) -> {
                 when(item.getType()).thenReturn((Material) context.arguments().getFirst());
                 when(item.getItemMeta()).thenReturn(mock(ItemMeta.class));
                 when(item.clone()).thenReturn(item);
             })) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            DefaultDialogService service = new DefaultDialogService(owner, mock(GuiService.class),
                    mock(LanguageService.class), mock(LeDatScheduler.class), null);
            var build = DefaultDialogService.class.getDeclaredMethod("buildTemplate", Plugin.class, String.class,
                    org.bukkit.entity.Player.class, Map.class, Map.class, Consumer.class);
            build.setAccessible(true);
            PlatformDialog dialog = (PlatformDialog) build.invoke(service, owner, "confirm", null,
                    Map.of("rows", "6"), Map.of(), null);
            assertEquals(36, dialog.fallbackSize());
            assertEquals("Confirm purchase", PLAIN.serialize(dialog.title()));
            assertEquals("<shift:-12><glyph:row_4>Shop 4", PLAIN.serialize(dialog.inventoryTitle()));
            assertEquals(Material.AIR, dialog.fallbackFillItem().getType());
            Files.writeString(temp.resolve("dialogs/filled.yml"), """
                    title: 'Explicit fill'
                    fallback:
                      fill-item:
                        material: BLACK_STAINED_GLASS_PANE
                    """);
            PlatformDialog filled = (PlatformDialog) build.invoke(service, owner, "filled", null,
                    Map.of(), Map.of(), null);
            assertEquals(Material.BLACK_STAINED_GLASS_PANE, filled.fallbackFillItem().getType());
        }
    }

    private static PlatformDialog.Builder builder() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("TitleTest");
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        return PlatformDialog.builder(plugin, "confirm.v2").fallbackFillItem(item).fallbackInfoItem(item);
    }

    private static YamlConfiguration overrideConfig() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                dialogs:
                  fallback:
                    inventory-titles:
                      TitleTest:
                        'confirm.v2': '<shift:-12><glyph:row_{rows}>{title}'
                        '*': 'Unused wildcard'
                      '*':
                        'confirm.v2': 'Unused wildcard owner'
                """);
        return config;
    }
}
