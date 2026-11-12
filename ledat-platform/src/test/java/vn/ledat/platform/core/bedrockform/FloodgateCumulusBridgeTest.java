package vn.ledat.platform.core.bedrockform;

import org.junit.jupiter.api.Test;
import vn.ledat.platform.api.bedrockform.BedrockFormDisplayMode;
import vn.ledat.platform.api.bedrockform.BedrockFormInputType;
import vn.ledat.platform.core.dialog.DialogTestSupport;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FloodgateCumulusBridgeTest {
    @Test
    void actualInstalledCumulusBuildsAndDecodesAllControlsWithLabels() throws Exception {
        for (Path jar : fixtures()) {
            try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
                FloodgateCumulusBridge bridge = bridge(loader);
                List<RenderedBedrockForm.RenderedInput> inputs = inputs();
                AtomicReference<Map<String, Object>> received = new AtomicReference<>();
                AtomicInteger valid = new AtomicInteger();
                AtomicInteger closed = new AtomicInteger();
                AtomicInteger invalid = new AtomicInteger();
                BedrockFormBridge.BridgeResponseHandler handler = new BedrockFormBridge.BridgeResponseHandler() {
                            public void valid(int button, Map<String, Object> values, Object source) { valid.incrementAndGet(); received.set(values); }
                            public void closed(Object source) { closed.incrementAndGet(); }
                            public void invalid(Object source) { invalid.incrementAndGet(); }
                        };
                Object form = build(bridge, inputs, handler);
                handle(loader, form, "[null,\"Target\",true,1,5,0]");
                assertEquals(1, valid.get(), jar.toString());
                assertEquals("Target", received.get().get("name"));
                assertEquals(true, received.get().get("enabled"));
                assertEquals("Same label", received.get().get("choice"));
                assertEquals(1, received.get().get("choice.index"));
                assertEquals(5f, ((Number) received.get().get("duration")).floatValue());
                assertEquals(0, received.get().get("step.index"));
                handle(loader, form, "[]");
                assertEquals(1, valid.get());
                assertEquals(0, invalid.get());
                assertEquals(0, closed.get());
                handle(loader, build(bridge, inputs, handler), "[]");
                assertEquals(1, invalid.get());
                assertEquals(0, closed.get());
                handle(loader, build(bridge, inputs, handler), "null");
                assertEquals(1, closed.get());
            }
        }
    }

    @Test
    void realResultHandlerRoutesDecoderFailureToInvalidWithoutEmptyData() throws Exception {
        for (Path jar : fixtures()) {
            try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
                FloodgateCumulusBridge bridge = bridge(loader);
                AtomicInteger valid = new AtomicInteger();
                AtomicInteger invalid = new AtomicInteger();
                Object form = DialogTestSupport.call(bridge, "buildCustom", new Class<?>[]{RenderedBedrockForm.class, BedrockFormBridge.BridgeResponseHandler.class},
                        new RenderedBedrockForm(BedrockFormDisplayMode.CUSTOM, "Inputs", "", List.of(), inputs()),
                        new BedrockFormBridge.BridgeResponseHandler() {
                            public void valid(int button, Map<String, Object> values, Object source) { valid.incrementAndGet(); }
                            public void closed(Object source) { }
                            public void invalid(Object source) { invalid.incrementAndGet(); }
                        });
                Class<?> responseType = Class.forName("org.geysermc.cumulus.response.CustomFormResponse", true, loader);
                Object unreadable = mock(responseType, call -> { throw new IllegalArgumentException("Unreadable typed response"); });
                Class<?> resultType = Class.forName("org.geysermc.cumulus.response.result.FormResponseResult", true, loader);
                Object validResult = resultType.getMethod("valid", Class.forName("org.geysermc.cumulus.response.FormResponse", true, loader)).invoke(null, unreadable);
                form.getClass().getMethod("callResultHandler", resultType).invoke(form, validResult);
                assertEquals(0, valid.get());
                assertEquals(1, invalid.get());
            }
        }
    }

    private static FloodgateCumulusBridge bridge(ClassLoader loader) throws Exception {
        // The network API is excluded; the real bridge's Cumulus builders,
        // serializers, positional responses and handler wiring execute unchanged.
        FloodgateCumulusBridge bridge = mock(FloodgateCumulusBridge.class, CALLS_REAL_METHODS);
        DialogTestSupport.set(bridge, "customFormClass", Class.forName("org.geysermc.cumulus.form.CustomForm", true, loader));
        return bridge;
    }

    private static Object build(FloodgateCumulusBridge bridge, List<RenderedBedrockForm.RenderedInput> inputs, BedrockFormBridge.BridgeResponseHandler handler) throws Exception {
        return DialogTestSupport.call(bridge, "buildCustom", new Class<?>[]{RenderedBedrockForm.class, BedrockFormBridge.BridgeResponseHandler.class},
                new RenderedBedrockForm(BedrockFormDisplayMode.CUSTOM, "Inputs", "", List.of(), inputs), handler);
    }

    private static void handle(ClassLoader loader, Object form, String json) throws Exception {
        Class<?> definition = Class.forName("org.geysermc.cumulus.form.impl.custom.CustomFormDefinition", true, loader);
        Object instance = definition.getMethod("instance").invoke(null);
        definition.getMethod("handleFormResponse", Class.forName("org.geysermc.cumulus.form.Form", true, loader), String.class).invoke(instance, form, json);
    }

    private static List<RenderedBedrockForm.RenderedInput> inputs() {
        List<RenderedBedrockForm.RenderedInput> inputs = new ArrayList<>();
        inputs.add(input("label", BedrockFormInputType.LABEL, List.of()));
        inputs.add(input("name", BedrockFormInputType.TEXT, List.of()));
        inputs.add(input("enabled", BedrockFormInputType.BOOLEAN, List.of()));
        inputs.add(input("choice", BedrockFormInputType.DROPDOWN, List.of("Same label", "Same label")));
        inputs.add(input("duration", BedrockFormInputType.SLIDER, List.of()));
        inputs.add(input("step", BedrockFormInputType.STEP_SLIDER, List.of("A", "B")));
        return inputs;
    }

    private static RenderedBedrockForm.RenderedInput input(String key, BedrockFormInputType type, List<String> options) {
        return new RenderedBedrockForm.RenderedInput(key, type, key, "", "initial", true, options, 0, 2, 60, 1, 5);
    }

    private static List<Path> fixtures() throws Exception {
        String provided = System.getenv("PLATFORM_DIALOG_FLOODGATE_FIXTURES");
        if (provided != null && !provided.isBlank()) return java.util.Arrays.stream(provided.split(java.io.File.pathSeparator)).map(Path::of).toList();
        Path cached = Path.of(System.getProperty("user.home"), ".gradle", "caches", "modules-2", "files-2.1", "org.geysermc.cumulus", "cumulus", "1.1.2");
        try (var paths = Files.walk(cached)) {
            return List.of(paths.filter(p -> p.getFileName().toString().equals("cumulus-1.1.2.jar")).findFirst()
                    .orElseThrow(() -> new AssertionError("Actual Cumulus1.1.2 or installed Floodgate fixture is required")));
        }
    }
}
