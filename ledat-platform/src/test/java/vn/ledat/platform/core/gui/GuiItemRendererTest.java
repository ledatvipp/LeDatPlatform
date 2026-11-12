package vn.ledat.platform.core.gui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerProfile;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import vn.ledat.platform.api.gui.GuiItemDefinition;
import vn.ledat.platform.api.profile.GameProfileService;
import vn.ledat.platform.api.scheduler.EntityTaskOutcome;
import vn.ledat.platform.api.scheduler.LeDatScheduler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GuiItemRendererTest {
    private static final String HASH = "a".repeat(64);
    private static final String TEXTURE_URL = "https://textures.minecraft.net/texture/" + HASH;

    @Test
    void untrustedTagsAndReplacementCharactersRemainLiteralWithoutRecursiveExpansion() {
        String input = "<red>Admin</red> $\\ {amount}";
        String rendered = GuiItemRenderer.replace("<white>Người chơi: {player}", Map.of("player", input, "amount", "500"), true);
        assertEquals("Người chơi: " + input, PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(rendered)));
        assertThrows(IllegalArgumentException.class, () -> GuiItemRenderer.replace("{missing}", Map.of(), true));
        assertThrows(IllegalArgumentException.class, () -> GuiItemRenderer.replace("{player}", Map.of("player", "name\nnew line"), true));
    }

    @Test
    void retirementNeverResolvesAProfileOrConstructsAnItem() {
        LeDatScheduler scheduler = mock(LeDatScheduler.class);
        GameProfileService profiles = mock(GameProfileService.class);
        Plugin owner = mock(Plugin.class);
        Player viewer = mock(Player.class);
        when(scheduler.entityOperation(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(EntityTaskOutcome.retired()));
        var definition = new GuiItemDefinition(org.bukkit.Material.PLAYER_HEAD, 1, null, List.of(), null, null,
                new GuiItemDefinition.Head(GuiItemDefinition.HeadSource.PLAYER, "{player_uuid}", "{player}", "", org.bukkit.Material.PLAYER_HEAD));
        var future = new GuiItemRenderer(profiles, scheduler).render(owner, viewer, definition, Map.of());
        assertThrows(CompletionException.class, future::join);
        verifyNoInteractions(profiles);
    }

    @Test
    void playerLookupAndLateMetaConstructionBothUseOwnerScheduling() {
        try (Harness harness = new Harness()) {
            CompletableFuture<Optional<PlayerProfile>> pending = new CompletableFuture<>();
            when(harness.profiles.resolve(any(UUID.class), anyString())).thenAnswer(invocation -> {
                assertTrue(harness.onOwner.get(), "Profile request starts on the owner scheduler");
                return pending;
            });
            var definition = definition(GuiItemDefinition.HeadSource.PLAYER, "");
            var result = harness.renderer.render(harness.owner, harness.viewer, definition, Map.of());
            assertFalse(result.isDone());
            PlayerProfile profile = mock(PlayerProfile.class);
            when(profile.clone()).thenReturn(profile);
            pending.complete(Optional.of(profile));
            assertEquals(Material.PLAYER_HEAD, result.join().getType());
            verify(harness.profiles).resolve(harness.playerId, "Alice");
            verify(profile).clone();
            verify(harness.scheduler, times(2)).entityOperation(any(), any(), any());
        }
    }

    @Test
    void customHeadModesShareOnlyClonedTextureTemplatesAndPreserveEachViewersText() {
        try (Harness harness = new Harness()) {
            String encoded = Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"" + TEXTURE_URL + "\"}}}")
                    .getBytes(StandardCharsets.UTF_8));
            ItemStack first = harness.renderer.render(harness.owner, harness.viewer,
                    definition(GuiItemDefinition.HeadSource.TEXTURE_URL, TEXTURE_URL), Map.of("player", "Alice")).join();
            ItemStack second = harness.renderer.render(harness.owner, harness.viewer,
                    definition(GuiItemDefinition.HeadSource.TEXTURE_HASH, HASH), Map.of("player", "Bob")).join();
            ItemStack third = harness.renderer.render(harness.owner, harness.viewer,
                    definition(GuiItemDefinition.HeadSource.TEXTURE_BASE64, encoded), Map.of("player", "Carol")).join();
            first.setAmount(20);
            assertEquals(1, second.getAmount());
            assertEquals("Alice", plain(first.getItemMeta().displayName()));
            assertEquals("Bob", plain(second.getItemMeta().displayName()));
            assertEquals("Carol", plain(third.getItemMeta().displayName()));
            assertEquals(" ", plain(third.getItemMeta().lore().get(0)));
            verify(harness.profiles, times(1)).texturedHead(any(), isNull());
        }
    }

    @Test
    void invalidTextureReturnsConfiguredFallbackWithoutAProviderRequest() {
        try (Harness harness = new Harness()) {
            var head = new GuiItemDefinition.Head(GuiItemDefinition.HeadSource.TEXTURE_URL, "", "", "https://evil.test/texture/" + HASH, Material.CLOCK);
            var definition = new GuiItemDefinition(Material.PLAYER_HEAD, 1, "{player}", List.of(" "), null, null, head);
            ItemStack item = harness.renderer.render(harness.owner, harness.viewer, definition, Map.of()).join();
            assertEquals(Material.CLOCK, item.getType());
            verifyNoInteractions(harness.profiles);
        }
    }

    private static GuiItemDefinition definition(GuiItemDefinition.HeadSource source, String value) {
        return new GuiItemDefinition(Material.PLAYER_HEAD, 1, "{player}", List.of(" ", "<dark_gray> ○ <white>Hồ sơ"), null, null,
                new GuiItemDefinition.Head(source, "{player_uuid}", "{player}", value, Material.PLAYER_HEAD));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static SkullMeta meta(Component initialName, List<Component> initialLore) {
        AtomicReference<Component> name = new AtomicReference<>(initialName);
        AtomicReference<List<Component>> lore = new AtomicReference<>(initialLore);
        SkullMeta result = mock(SkullMeta.class);
        when(result.clone()).thenAnswer(invocation -> meta(name.get(), lore.get()));
        when(result.displayName()).thenAnswer(invocation -> name.get());
        doAnswer(invocation -> { name.set(invocation.getArgument(0)); return null; }).when(result).displayName(any(Component.class));
        when(result.lore()).thenAnswer(invocation -> lore.get());
        doAnswer(invocation -> { lore.set(List.copyOf(invocation.getArgument(0))); return null; }).when(result).lore(anyList());
        return result;
    }

    private static final class Harness implements AutoCloseable {
        final LeDatScheduler scheduler = mock(LeDatScheduler.class);
        final GameProfileService profiles = mock(GameProfileService.class);
        final Plugin owner = mock(Plugin.class);
        final Player viewer = mock(Player.class);
        final UUID playerId = UUID.randomUUID();
        final AtomicBoolean onOwner = new AtomicBoolean();
        final GuiItemRenderer renderer = new GuiItemRenderer(profiles, scheduler);
        final MockedConstruction<ItemStack> construction;

        Harness() {
            construction = mockConstruction(ItemStack.class, (item, context) -> {
                assertTrue(onOwner.get(), "Item construction returns to the owner scheduler");
                configureItem(item, (Material) context.arguments().get(0), 1, meta(null, null));
            });
            when(owner.isEnabled()).thenReturn(true);
            when(viewer.isOnline()).thenReturn(true);
            when(viewer.getUniqueId()).thenReturn(playerId);
            when(viewer.getName()).thenReturn("Alice");
            when(scheduler.entityOperation(any(), any(), any())).thenAnswer(invocation -> {
                onOwner.set(true);
                try {
                    Object value = ((Supplier<?>) invocation.getArgument(2)).get();
                    return CompletableFuture.completedFuture(EntityTaskOutcome.executed(value));
                } catch (Throwable error) {
                    return CompletableFuture.completedFuture(EntityTaskOutcome.failed(error));
                } finally {
                    onOwner.set(false);
                }
            });
            when(profiles.texturedHead(any(), isNull())).thenAnswer(invocation -> {
                assertTrue(onOwner.get());
                return new ItemStack(Material.PLAYER_HEAD);
            });
        }

        private static void configureItem(ItemStack item, Material material, int initialAmount, SkullMeta initialMeta) {
            AtomicInteger amount = new AtomicInteger(initialAmount);
            AtomicReference<SkullMeta> itemMeta = new AtomicReference<>(initialMeta);
            when(item.getType()).thenReturn(material);
            when(item.getAmount()).thenAnswer(invocation -> amount.get());
            doAnswer(invocation -> { amount.set(invocation.getArgument(0)); return null; }).when(item).setAmount(anyInt());
            when(item.getItemMeta()).thenAnswer(invocation -> itemMeta.get().clone());
            when(item.setItemMeta(any())).thenAnswer(invocation -> {
                itemMeta.set(((SkullMeta) invocation.getArgument(0)).clone());
                return true;
            });
            when(item.hasItemMeta()).thenReturn(true);
            when(item.clone()).thenAnswer(invocation -> {
                ItemStack copy = mock(ItemStack.class);
                configureItem(copy, material, amount.get(), itemMeta.get().clone());
                return copy;
            });
        }

        @Override public void close() { construction.close(); }
    }
}
