package vn.ledat.platform.core.dialog;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import vn.ledat.platform.core.bedrockform.BedrockTextRenderer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDialogBedrockProjectionTest {

    @Test
    void bedrockFallbackProjectsOnlyTheClonedItem() {
        ItemStack source = mock(ItemStack.class);
        ItemStack clone = mock(ItemStack.class);
        ItemMeta cloneMeta = mock(ItemMeta.class);
        Component object = Component.empty().hoverEvent(Component.text("object-marker"));
        Component displayName = Component.text("Open ").append(object);
        BedrockTextRenderer renderer = mock(BedrockTextRenderer.class);
        when(source.clone()).thenReturn(clone);
        when(clone.getItemMeta()).thenReturn(cloneMeta);
        when(cloneMeta.displayName()).thenReturn(displayName);
        when(cloneMeta.lore()).thenReturn(List.of(object));
        when(renderer.render(displayName)).thenReturn("Open Compass");
        when(renderer.render(object)).thenReturn("Compass");

        ItemStack result = DefaultDialogService.fallbackItem(source, true, renderer);

        assertSame(clone, result);
        verify(source, never()).getItemMeta();
        verify(cloneMeta).displayName(Component.text("Open Compass"));
        verify(cloneMeta).lore(List.of(Component.text("Compass")));
        verify(clone).setItemMeta(cloneMeta);
    }

    @Test
    void javaFallbackReturnsTheOriginalItemWithoutProjection() {
        ItemStack source = mock(ItemStack.class);
        BedrockTextRenderer renderer = mock(BedrockTextRenderer.class);

        ItemStack result = DefaultDialogService.fallbackItem(source, false, renderer);

        assertSame(source, result);
        verify(source, never()).clone();
        verify(source, never()).getItemMeta();
    }
}
