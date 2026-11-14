package vn.ledat.platform.core.command;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import vn.ledat.platform.api.command.CommandContext;
import vn.ledat.platform.api.language.LanguageService;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandOperatorAccessTest {
    @Test void opCanDiscoverAndExecuteWhileDeniedNonOpCannot() throws Exception {
        var plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getName()).thenReturn("AccessTest");
        var languages = mock(LanguageService.class);
        var service = new DefaultCommandService(plugin, languages, null, null);
        var ran = new AtomicBoolean();
        var route = service.command(plugin, "admincheck").permission("example.admin").exactArgs(0)
                .executor(ctx -> ran.set(true));
        var sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("operator");
        when(sender.isOp()).thenReturn(true);
        var visible = java.util.Arrays.stream(DefaultCommandService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("canSee")).findFirst().orElseThrow();
        visible.setAccessible(true);
        var execute = java.util.Arrays.stream(DefaultCommandService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("executeNodeDirect")).findFirst().orElseThrow();
        execute.setAccessible(true);
        assertTrue((boolean) visible.invoke(service, sender, route));
        execute.invoke(service, route, new CommandContext(plugin, sender, "admincheck", new String[0]));
        assertTrue(ran.get());
        ran.set(false);
        when(sender.isOp()).thenReturn(false);
        assertFalse((boolean) visible.invoke(service, sender, route));
        execute.invoke(service, route, new CommandContext(plugin, sender, "admincheck", new String[0]));
        assertFalse(ran.get());
        when(sender.hasPermission("example.admin")).thenReturn(true);
        assertTrue((boolean) visible.invoke(service, sender, route));
        execute.invoke(service, route, new CommandContext(plugin, sender, "admincheck", new String[0]));
        assertTrue(ran.get());
    }
}
