package vn.ledat.platform.core.dialog;

import io.papermc.paper.registry.RegistryAccess;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import vn.ledat.platform.api.bedrockform.BedrockFormService;
import vn.ledat.platform.api.gui.GuiService;
import vn.ledat.platform.api.language.LanguageService;
import vn.ledat.platform.api.scheduler.LeDatScheduler;
import vn.ledat.platform.api.scheduler.LeDatTask;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Isolates Bukkit services while exercising real core session and input code. */
public final class DialogTestSupport implements AutoCloseable {
    public final Plugin owner = mock(Plugin.class);
    public final Player player = mock(Player.class);
    public final GuiService guis = mock(GuiService.class);
    public final LanguageService languages = mock(LanguageService.class);
    public final LeDatScheduler scheduler = mock(LeDatScheduler.class);
    public final BedrockFormService forms = mock(BedrockFormService.class);
    public final YamlConfiguration config = new YamlConfiguration();
    public final Deque<Runnable> queued = new ArrayDeque<>();
    private final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
    private final MockedStatic<RegistryAccess> registries = mockStatic(RegistryAccess.class);
    private final MockedConstruction<ItemStack> stacks;

    public DialogTestSupport() {
        RegistryAccess access = mock(RegistryAccess.class, invocation -> mock(Registry.class));
        registries.when(RegistryAccess::registryAccess).thenReturn(access);
        PluginManager plugins = mock(PluginManager.class);
        when(plugins.getPlugins()).thenReturn(new Plugin[0]);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        when(owner.getName()).thenReturn("InputTest");
        when(owner.getConfig()).thenReturn(config);
        when(owner.getLogger()).thenReturn(Logger.getLogger("DialogInputTests"));
        when(owner.isEnabled()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        doAnswer(call -> {
            queued.add(call.getArgument(2, Runnable.class));
            return LeDatTask.NOOP;
        }).when(scheduler).entity(any(), any(), any());
        stacks = mockConstruction(ItemStack.class, (item, context) -> {
            when(item.clone()).thenReturn(item);
            when(item.getType()).thenReturn((Material) context.arguments().getFirst());
        });
    }

    public DefaultDialogService dialogService() {
        return new DefaultDialogService(owner, guis, languages, scheduler, null, forms);
    }

    public void drain() {
        while (!queued.isEmpty()) queued.removeFirst().run();
    }

    public static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    public static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    public static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }

    @Override
    public void close() {
        stacks.close();
        registries.close();
        bukkit.close();
    }
}
