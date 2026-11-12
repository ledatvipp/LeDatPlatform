package vn.ledat.platform.core.dialog;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import vn.ledat.platform.api.dialog.DialogInputSpec;
import vn.ledat.platform.api.dialog.DialogInputType;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeDialogBridgeTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void actualPaperFactoriesPreserveAllFourTypedDefinitions() throws Exception {
        for (String version : List.of("1.21.8", "1.21.11")) {
            Path jar = paperJar(version);
            try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
                 DialogTestSupport ignored = new DialogTestSupport()) {
                Class<?> providerClass = Class.forName("io.papermc.paper.registry.data.dialog.DialogInstancesProvider", true, loader);
                Class<?> inputClass = Class.forName("io.papermc.paper.registry.data.dialog.input.DialogInput", true, loader);
                // The API's real static factories execute here. Only its server SPI is
                // isolated, since a unit JVM has no Minecraft registry implementation.
                Object provider = mock(providerClass, call -> {
                    var method = call.getMethod();
                    Map<String, Object> properties = new HashMap<>();
                    properties.put("key", call.getArgument(0));
                    properties.put("label", call.getArgument(1));
                    if (method.getName().equals("singleOptionEntry")) {
                        properties.put("id", call.getArgument(0));
                        properties.put("display", call.getArgument(1));
                        properties.put("initial", call.getArgument(2));
                        return mock(method.getReturnType(), getter -> properties.get(getter.getMethod().getName()));
                    }
                    if (method.getName().equals("numberRangeBuilder")) {
                        properties.put("start", call.getArgument(2));
                        properties.put("end", call.getArgument(3));
                    }
                    if (method.getName().equals("singleOptionBuilder")) properties.put("entries", call.getArgument(2));
                    AtomicReference<Object> self = new AtomicReference<>();
                    Object builder = mock(method.getReturnType(), setter -> {
                        if (setter.getMethod().getName().equals("build")) {
                            return mock(setter.getMethod().getReturnType(), getter -> properties.get(getter.getMethod().getName()));
                        }
                        if (setter.getArguments().length == 1) properties.put(setter.getMethod().getName(), setter.getArgument(0));
                        return self.get();
                    });
                    self.set(builder);
                    return builder;
                });
                try (MockedStatic providerStatic = mockStatic(providerClass)) {
                    providerStatic.when(() -> providerClass.getMethod("instance").invoke(null)).thenReturn(provider);
                    NativeDialogBridge bridge = new NativeDialogBridge();
                    DialogTestSupport.set(bridge, "dialogInputClass", inputClass);
                    List<DialogInputSpec> specs = List.of(
                            DialogInputSpec.text("name", Component.text("Tên")).initial("Player").maxLength(16).build(),
                            DialogInputSpec.builder("enabled", DialogInputType.BOOLEAN).initialBoolean(true).booleanValues("yes", "no").build(),
                            DialogInputSpec.builder("duration", DialogInputType.NUMBER_RANGE).range(2, 60).initialNumber(5f).step(1f).build(),
                            DialogInputSpec.builder("kind", DialogInputType.SINGLE_OPTION).labelVisible(false)
                                    .option("stable-a", Component.text("Nhãn A"), false)
                                    .option("stable-b", Component.text("Nhãn B"), true).build());
                    List<?> inputs = (List<?>) DialogTestSupport.call(bridge, "buildInputs", new Class<?>[]{List.class}, specs);
                    assertEquals(4, inputs.size(), version);
                    assertEquals("Player", getter(inputs.get(0), "initial"));
                    assertEquals(16, getter(inputs.get(0), "maxLength"));
                    assertEquals(true, getter(inputs.get(1), "initial"));
                    assertEquals("yes", getter(inputs.get(1), "onTrue"));
                    assertEquals(5f, getter(inputs.get(2), "initial"));
                    assertEquals(1f, getter(inputs.get(2), "step"));
                    assertEquals(2f, getter(inputs.get(2), "start"));
                    List<?> entries = (List<?>) getter(inputs.get(3), "entries");
                    assertEquals("stable-b", getter(entries.get(1), "id"));
                    assertEquals(Component.text("Nhãn B"), getter(entries.get(1), "display"));
                    assertEquals(true, getter(entries.get(1), "initial"));
                    assertEquals(false, getter(inputs.get(3), "labelVisible"));
                }
            }
        }
    }

    @Test
    void missingOrInvalidInputCapabilityFailsTheCompleteInputList() throws Exception {
        try (DialogTestSupport ignored = new DialogTestSupport()) {
            NativeDialogBridge bridge = new NativeDialogBridge();
            DialogTestSupport.set(bridge, "dialogInputClass", null);
            List<DialogInputSpec> specs = List.of(DialogInputSpec.text("text", Component.text("Name")).build());
            assertThrows(IllegalStateException.class, () -> DialogTestSupport.call(bridge, "buildInputs", new Class<?>[]{List.class}, specs));
            DialogTestSupport.set(bridge, "dialogInputClass", String.class);
            assertThrows(IllegalStateException.class, () -> DialogTestSupport.call(bridge, "buildInputs", new Class<?>[]{List.class}, specs));
            DialogInputSpec empty = DialogInputSpec.builder("empty", DialogInputType.SINGLE_OPTION).build();
            assertThrows(IllegalArgumentException.class, () -> DialogTestSupport.call(bridge, "buildInput", new Class<?>[]{DialogInputSpec.class}, empty));
            DialogInputSpec number = DialogInputSpec.builder("number", DialogInputType.NUMBER_RANGE).range(Float.NaN, 10).build();
            assertThrows(IllegalArgumentException.class, () -> DialogTestSupport.call(bridge, "buildInput", new Class<?>[]{DialogInputSpec.class}, number));
        }
    }

    private static Object getter(Object target, String name) throws Exception {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static Path paperJar(String version) throws Exception {
        String env = System.getenv("PLATFORM_DIALOG_PAPER_" + version.replace('.', '_'));
        if (env != null) return Path.of(env);
        Path directory = Path.of(System.getProperty("user.home"), ".gradle", "caches", "modules-2", "files-2.1", "io.papermc.paper", "paper-api", version + "-R0.1-SNAPSHOT");
        try (var paths = Files.walk(directory)) {
            return paths.filter(p -> p.getFileName().toString().equals("paper-api-" + version + "-R0.1-SNAPSHOT.jar"))
                    .findFirst().orElseThrow(() -> new AssertionError("Actual Paper " + version + " API fixture is required"));
        }
    }
}
