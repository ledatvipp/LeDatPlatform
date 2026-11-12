package vn.ledat.platform.core.bedrockform;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.bedrockform.BedrockFormInputType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class FloodgateCumulusBridge implements BedrockFormBridge {
    private final Plugin platformPlugin;
    private final Detection detection;
    private final ClassLoader classLoader;
    private final Class<?> apiClass;
    private final boolean floodgateApi;
    private final Class<?> formClass;
    private final Class<?> simpleFormClass;
    private final Class<?> modalFormClass;
    private final Class<?> customFormClass;
    private final Object api;
    private final Method isBedrockMethod;
    private final boolean isBedrockByPresence;

    private FloodgateCumulusBridge(Plugin platformPlugin, Detection detection) throws ReflectiveOperationException {
        this.platformPlugin = platformPlugin;
        this.detection = detection;
        this.classLoader = Objects.requireNonNull(detection.classLoader(), "classLoader");
        if (hasClass("org.geysermc.floodgate.api.FloodgateApi", classLoader)) {
            this.apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi", false, classLoader);
            this.floodgateApi = true;
            this.api = apiClass.getMethod("getInstance").invoke(null);
        } else if (hasClass("org.geysermc.geyser.api.GeyserApi", classLoader)) {
            this.apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi", false, classLoader);
            this.floodgateApi = false;
            this.api = apiClass.getMethod("api").invoke(null);
        } else {
            throw new ClassNotFoundException("No FloodgateApi or GeyserApi class in resolved Bedrock form classloader");
        }
        if (this.api == null) {
            throw new IllegalStateException((floodgateApi ? "FloodgateApi#getInstance" : "GeyserApi#api") + " returned null");
        }
        this.formClass = Class.forName("org.geysermc.cumulus.form.Form", false, classLoader);
        this.simpleFormClass = Class.forName("org.geysermc.cumulus.form.SimpleForm", false, classLoader);
        this.modalFormClass = Class.forName("org.geysermc.cumulus.form.ModalForm", false, classLoader);
        this.customFormClass = Class.forName("org.geysermc.cumulus.form.CustomForm", false, classLoader);
        // Resolve the per-player check once; isBedrock runs on every form/dialog
        // open and a reflective getMethod lookup per call is avoidable cost.
        Method bedrockCheck = null;
        boolean byPresence = false;
        if (floodgateApi) {
            bedrockCheck = quietMethod(apiClass, "isFloodgatePlayer", UUID.class);
        } else {
            bedrockCheck = quietMethod(apiClass, "isBedrockPlayer", UUID.class);
            if (bedrockCheck == null) {
                bedrockCheck = quietMethod(apiClass, "connectionByUuid", UUID.class);
                byPresence = bedrockCheck != null;
            }
        }
        this.isBedrockMethod = bedrockCheck;
        this.isBedrockByPresence = byPresence;
    }

    private static Method quietMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static BedrockFormBridge create(Plugin platformPlugin) {
        Detection detection = detect(platformPlugin);
        if (!detection.available()) {
            return new BedrockFormBridge.Missing(detection);
        }
        try {
            return new FloodgateCumulusBridge(platformPlugin, detection);
        } catch (Throwable error) {
            String detail = detection.detail() + "; bridge init failed: " + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : " " + error.getMessage());
            platformPlugin.getLogger().log(Level.FINE, "Could not initialize Floodgate/Cumulus form bridge", error);
            return new BedrockFormBridge.Missing(new Detection(
                    detection.geyserPluginDetected(),
                    detection.floodgatePluginDetected(),
                    detection.floodgateClassDetected(),
                    detection.geyserApiClassDetected(),
                    detection.cumulusClassDetected(),
                    detection.classLoader(),
                    detail
            ));
        }
    }

    public static Detection detect(Plugin platformPlugin) {
        Plugin floodgate = findPlugin("floodgate", "Floodgate");
        Plugin geyser = findPlugin("Geyser", "Geyser-Spigot");
        List<ClassLoader> loaders = new ArrayList<>();
        if (floodgate != null) loaders.add(floodgate.getClass().getClassLoader());
        if (geyser != null) loaders.add(geyser.getClass().getClassLoader());
        if (platformPlugin != null) loaders.add(platformPlugin.getClass().getClassLoader());
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) loaders.add(context);
        loaders.add(ClassLoader.getSystemClassLoader());

        ClassLoader resolved = null;
        boolean floodgateClass = false;
        boolean geyserApiClass = false;
        boolean cumulusClass = false;
        for (ClassLoader loader : loaders.stream().filter(Objects::nonNull).distinct().toList()) {
            boolean hasFloodgate = hasClass("org.geysermc.floodgate.api.FloodgateApi", loader);
            boolean hasGeyserApi = hasClass("org.geysermc.geyser.api.GeyserApi", loader);
            boolean hasCumulus = hasClass("org.geysermc.cumulus.form.SimpleForm", loader)
                    && hasClass("org.geysermc.cumulus.form.ModalForm", loader)
                    && hasClass("org.geysermc.cumulus.form.CustomForm", loader);
            floodgateClass |= hasFloodgate;
            geyserApiClass |= hasGeyserApi;
            cumulusClass |= hasCumulus;
            if ((hasFloodgate || hasGeyserApi) && hasCumulus) {
                resolved = loader;
                break;
            }
        }
        String detail = "Floodgate plugin=" + (floodgate == null ? "missing" : floodgate.getName())
                + ", Geyser plugin=" + (geyser == null ? "missing" : geyser.getName())
                + ", FloodgateApi class=" + floodgateClass
                + ", GeyserApi class=" + geyserApiClass
                + ", Cumulus classes=" + cumulusClass;
        return new Detection(geyser != null, floodgate != null, floodgateClass, geyserApiClass, cumulusClass, resolved, detail);
    }

    @Override public boolean available() { return true; }
    @Override public boolean floodgatePluginDetected() { return detection.floodgatePluginDetected(); }
    @Override public boolean geyserPluginDetected() { return detection.geyserPluginDetected(); }
    @Override public boolean floodgateClassDetected() { return detection.floodgateClassDetected(); }
    @Override public boolean geyserApiClassDetected() { return detection.geyserApiClassDetected(); }
    @Override public boolean cumulusClassDetected() { return detection.cumulusClassDetected(); }
    @Override public String status() { return detection.detail(); }

    @Override
    public boolean isBedrock(UUID playerId) {
        if (playerId == null || api == null || isBedrockMethod == null) {
            return false;
        }
        try {
            Object result = isBedrockMethod.invoke(api, playerId);
            return isBedrockByPresence ? result != null : Boolean.TRUE.equals(result);
        } catch (Throwable error) {
            platformPlugin.getLogger().log(Level.FINE, (floodgateApi ? "Floodgate" : "Geyser")
                    + " Bedrock player check failed for " + playerId, error);
            return false;
        }
    }

    @Override
    public boolean send(Player player, RenderedBedrockForm form, BridgeResponseHandler handler) {
        if (player == null || form == null || handler == null) {
            return false;
        }
        try {
            Object built = switch (form.mode()) {
                case MODAL -> buildModal(form, handler);
                case CUSTOM -> buildCustom(form, handler);
                case AUTO, SIMPLE -> buildSimple(form, handler);
            };
            return sendForm(player.getUniqueId(), built);
        } catch (Throwable error) {
            platformPlugin.getLogger().log(Level.WARNING, "Could not send Bedrock form through Floodgate/Cumulus", error);
            return false;
        }
    }

    private Object buildSimple(RenderedBedrockForm form, BridgeResponseHandler handler) throws ReflectiveOperationException {
        BridgeResponseHandler response = once(handler);
        Object builder = simpleFormClass.getMethod("builder").invoke(null);
        invoke(builder, "title", form.title());
        invoke(builder, "content", form.content());
        for (RenderedBedrockForm.RenderedButton button : form.buttons()) {
            invoke(builder, "button", button.label());
        }
        validHandler(builder, result -> response.valid(clickedButtonId(unwrapValid(result)), Map.of(), result));
        closedHandler(builder, () -> response.closed(null));
        invalidHandler(builder, () -> response.invalid(null));
        return invoke(builder, "build");
    }

    private Object buildModal(RenderedBedrockForm form, BridgeResponseHandler handler) throws ReflectiveOperationException {
        BridgeResponseHandler response = once(handler);
        Object builder = modalFormClass.getMethod("builder").invoke(null);
        invoke(builder, "title", form.title());
        invoke(builder, "content", form.content());
        String first = form.buttons().isEmpty() ? "Confirm" : form.buttons().get(0).label();
        String second = form.buttons().size() < 2 ? "Cancel" : form.buttons().get(1).label();
        invoke(builder, "button1", first);
        invoke(builder, "button2", second);
        validHandler(builder, result -> response.valid(modalButtonIndex(unwrapValid(result)), Map.of(), result));
        closedHandler(builder, () -> response.closed(null));
        invalidHandler(builder, () -> response.invalid(null));
        return invoke(builder, "build");
    }

    private Object buildCustom(RenderedBedrockForm form, BridgeResponseHandler handler) throws ReflectiveOperationException {
        BridgeResponseHandler response = once(handler);
        Object builder = customFormClass.getMethod("builder").invoke(null);
        invoke(builder, "title", form.title());
        for (RenderedBedrockForm.RenderedInput input : form.inputs()) {
            addInput(builder, input);
        }
        validHandler(builder, result -> {
            Map<String, Object> inputs;
            try {
                inputs = readCustomInputs(unwrapValid(result), form.inputs());
            } catch (ReflectiveOperationException | IllegalArgumentException invalid) {
                response.invalid(result);
                return;
            }
            response.valid(0, inputs, result);
        });
        closedHandler(builder, () -> response.closed(null));
        invalidHandler(builder, () -> response.invalid(null));
        return invoke(builder, "build");
    }

    private static BridgeResponseHandler once(BridgeResponseHandler handler) {
        return new OneShotResponse(handler);
    }

    private static final class OneShotResponse implements BridgeResponseHandler {
        private final BridgeResponseHandler handler;
        private final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();

        private OneShotResponse(BridgeResponseHandler handler) { this.handler = handler; }

        public void valid(int button, Map<String, Object> inputs, Object source) {
            if (completed.compareAndSet(false, true)) handler.valid(button, inputs, source);
        }
        public void closed(Object source) {
            if (completed.compareAndSet(false, true)) handler.closed(source);
        }
        public void invalid(Object source) {
            if (completed.compareAndSet(false, true)) handler.invalid(source);
        }
    }

    private void addInput(Object builder, RenderedBedrockForm.RenderedInput input) throws ReflectiveOperationException {
        BedrockFormInputType type = input.type();
        switch (type) {
            case LABEL -> invoke(builder, "label", input.label());
            case TEXT -> invoke(builder, "input", input.label(), input.placeholder(), input.initial());
            case BOOLEAN -> invoke(builder, "toggle", input.label(), input.initialBoolean());
            case DROPDOWN -> invoke(builder, "dropdown", input.label(), input.options().isEmpty() ? List.of("") : input.options(), input.defaultOption());
            case SLIDER -> invoke(builder, "slider", input.label(), input.min(), input.max(), input.step(), input.initialNumber());
            case STEP_SLIDER -> invoke(builder, "stepSlider", input.label(), input.options().isEmpty() ? List.of("") : input.options(), input.defaultOption());
        }
    }

    private Map<String, Object> readCustomInputs(Object response, List<RenderedBedrockForm.RenderedInput> inputs) throws ReflectiveOperationException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            RenderedBedrockForm.RenderedInput input = inputs.get(i);
            if (input.type() == BedrockFormInputType.LABEL) {
                continue;
            }
            switch (input.type()) {
                case TEXT -> {
                    Object value = invoke(response, "asInput", i);
                    if (!(value instanceof String)) throw new IllegalArgumentException("Missing text input: " + input.key());
                    values.put(input.key(), value);
                }
                case BOOLEAN -> {
                    Object value = invoke(response, "asToggle", i);
                    if (!(value instanceof Boolean)) throw new IllegalArgumentException("Invalid boolean input: " + input.key());
                    values.put(input.key(), value);
                }
                case DROPDOWN, STEP_SLIDER -> {
                    Object value = invoke(response, input.type() == BedrockFormInputType.DROPDOWN ? "asDropdown" : "asStepSlider", i);
                    if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                            || number.doubleValue() != number.intValue() || number.intValue() < 0 || number.intValue() >= input.options().size()) {
                        throw new IllegalArgumentException("Invalid option input: " + input.key());
                    }
                    int index = number.intValue();
                    values.put(input.key() + ".index", index);
                    values.put(input.key(), input.options().get(index));
                }
                case SLIDER -> {
                    Object value = invoke(response, "asSlider", i);
                    if (!(value instanceof Number number) || !Float.isFinite(number.floatValue())) throw new IllegalArgumentException("Invalid slider input: " + input.key());
                    values.put(input.key(), value);
                }
                case LABEL -> {
                }
            }
        }
        return values;
    }

    private Object unwrapValid(Object result) {
        if (result == null) {
            return null;
        }
        try {
            return result.getClass().getMethod("response").invoke(result);
        } catch (Throwable ignored) {
            return result;
        }
    }

    private int clickedButtonId(Object response) {
        return number(invokeQuiet(response, "clickedButtonId"), -1);
    }

    private int modalButtonIndex(Object response) {
        Object clickedFirst = invokeQuiet(response, "clickedFirst");
        if (clickedFirst instanceof Boolean bool) {
            return bool ? 0 : 1;
        }
        return clickedButtonId(response);
    }

    private int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private void validHandler(Object builder, Consumer<Object> consumer) throws ReflectiveOperationException {
        invoke(builder, "validResultHandler", consumer);
    }

    private void closedHandler(Object builder, Runnable runnable) throws ReflectiveOperationException {
        invoke(builder, "closedResultHandler", runnable);
    }

    private void invalidHandler(Object builder, Runnable runnable) throws ReflectiveOperationException {
        invoke(builder, "invalidResultHandler", runnable);
    }

    private boolean sendForm(UUID playerId, Object form) throws ReflectiveOperationException {
        Method method = findSendFormMethod(form);
        if (method == null) {
            throw new NoSuchMethodException("Bedrock form API sendForm(UUID, Form)");
        }
        Object result = method.invoke(api, playerId, form);
        return !(result instanceof Boolean bool) || bool;
    }

    private Method findSendFormMethod(Object form) {
        for (Method method : api.getClass().getMethods()) {
            if (!method.getName().equals("sendForm") || method.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (!types[0].isAssignableFrom(UUID.class) && !UUID.class.isAssignableFrom(types[0])) {
                continue;
            }
            if (types[1].isInstance(form) || types[1].isAssignableFrom(formClass)) {
                return method;
            }
        }
        return null;
    }

    private Object invoke(Object target, String name, Object... args) throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), name, args);
        if (method == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
        }
        return method.invoke(target, args);
    }

    private Object invokeQuiet(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            return invoke(target, name, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Method findMethod(Class<?> type, String name, Object... args) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (!matches(parameterTypes[i], args[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return method;
            }
        }
        return null;
    }

    private boolean matches(Class<?> type, Object arg) {
        if (arg == null) {
            return !type.isPrimitive();
        }
        if (type.isPrimitive()) {
            if (type == boolean.class) return arg instanceof Boolean;
            if (type == int.class) return arg instanceof Integer;
            if (type == float.class) return arg instanceof Float;
            if (type == double.class) return arg instanceof Double || arg instanceof Float;
            if (type == long.class) return arg instanceof Long || arg instanceof Integer;
            return false;
        }
        return type.isInstance(arg);
    }

    private static Plugin findPlugin(String... names) {
        for (String name : names) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
            if (plugin != null && plugin.isEnabled()) {
                return plugin;
            }
        }
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            for (String name : names) {
                if (plugin.isEnabled() && plugin.getName().equalsIgnoreCase(name)) {
                    return plugin;
                }
            }
        }
        return null;
    }

    private static boolean hasClass(String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
