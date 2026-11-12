package vn.ledat.platform.core.dialog;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import vn.ledat.platform.api.dialog.DialogButton;
import vn.ledat.platform.api.dialog.DialogInputSpec;
import vn.ledat.platform.api.dialog.DialogInputType;
import vn.ledat.platform.api.dialog.PlatformDialog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Reflection bridge for Paper's experimental Dialog API.
 *
 * <p>LeDatPlatform intentionally avoids direct imports from the Paper dialog
 * package so the same jar can still boot on Paper 1.21.4/1.21.6 and route to
 * inventory fallback. All missing/changed methods degrade gracefully.</p>
 */
final class NativeDialogBridge {
    private final boolean available;
    private final String status;

    private final Class<?> dialogClass;
    private final Class<?> dialogBaseClass;
    private final Class<?> dialogAfterActionClass;
    private final Class<?> dialogBodyClass;
    private final Class<?> dialogInputClass;
    private final Class<?> dialogTypeClass;
    private final Class<?> actionButtonClass;
    private final Class<?> dialogActionClass;
    private final Class<? extends Event> customClickEventClass;

    private final Method dialogCreate;
    private final Method dialogBaseCreate;
    private final Method dialogBodyPlainMessage;
    private final Method dialogBodyItem;
    private final Method dialogTypeNotice;
    private final Method dialogTypeNoticeWithButton;
    private final Method dialogTypeConfirmation;
    private final Method dialogTypeMultiAction;
    private final Method actionButtonCreate;
    private final Method dialogActionCustomClick;

    @SuppressWarnings("unchecked")
    NativeDialogBridge() {
        Class<?> dialog = null;
        Class<?> dialogBase = null;
        Class<?> afterAction = null;
        Class<?> dialogBody = null;
        Class<?> dialogInput = null;
        Class<?> dialogType = null;
        Class<?> actionButton = null;
        Class<?> dialogAction = null;
        Class<? extends Event> clickEvent = null;
        Method create = null;
        Method baseCreate = null;
        Method plainMessage = null;
        Method itemBody = null;
        Method notice = null;
        Method noticeWithButton = null;
        Method confirmation = null;
        Method multiAction = null;
        Method buttonCreate = null;
        Method customClick = null;
        boolean ok = false;
        String message;
        try {
            dialog = Class.forName("io.papermc.paper.dialog.Dialog");
            dialogBase = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase");
            afterAction = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase$DialogAfterAction");
            dialogBody = Class.forName("io.papermc.paper.registry.data.dialog.body.DialogBody");
            try {
                dialogInput = Class.forName("io.papermc.paper.registry.data.dialog.input.DialogInput");
            } catch (ClassNotFoundException ignored) {
                dialogInput = null;
            }
            dialogType = Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType");
            actionButton = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton");
            dialogAction = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogAction");
            clickEvent = (Class<? extends Event>) Class.forName("io.papermc.paper.event.player.PlayerCustomClickEvent").asSubclass(Event.class);

            create = dialog.getMethod("create", Consumer.class);
            baseCreate = dialogBase.getMethod("create", Component.class, Component.class, boolean.class, boolean.class, afterAction, List.class, List.class);
            plainMessage = dialogBody.getMethod("plainMessage", Component.class);
            try {
                Class<?> plainMsgClass = Class.forName("io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody");
                itemBody = dialogBody.getMethod("item", org.bukkit.inventory.ItemStack.class, plainMsgClass, boolean.class, boolean.class, int.class, int.class);
            } catch (Throwable ignored) {
                itemBody = null;
            }
            notice = dialogType.getMethod("notice");
            noticeWithButton = dialogType.getMethod("notice", actionButton);
            confirmation = dialogType.getMethod("confirmation", actionButton, actionButton);
            multiAction = dialogType.getMethod("multiAction", List.class, actionButton, int.class);
            buttonCreate = actionButton.getMethod("create", Component.class, Component.class, int.class, dialogAction);
            customClick = findCustomClick(dialogAction);
            ok = customClick != null;
            message = ok ? "native Paper Dialog API detected" : "DialogAction.customClick(Key, BinaryTagHolder) not found";
        } catch (Throwable error) {
            message = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        this.available = ok;
        this.status = message;
        this.dialogClass = dialog;
        this.dialogBaseClass = dialogBase;
        this.dialogAfterActionClass = afterAction;
        this.dialogBodyClass = dialogBody;
        this.dialogInputClass = dialogInput;
        this.dialogTypeClass = dialogType;
        this.actionButtonClass = actionButton;
        this.dialogActionClass = dialogAction;
        this.customClickEventClass = clickEvent;
        this.dialogCreate = create;
        this.dialogBaseCreate = baseCreate;
        this.dialogBodyPlainMessage = plainMessage;
        this.dialogBodyItem = itemBody;
        this.dialogTypeNotice = notice;
        this.dialogTypeNoticeWithButton = noticeWithButton;
        this.dialogTypeConfirmation = confirmation;
        this.dialogTypeMultiAction = multiAction;
        this.actionButtonCreate = buttonCreate;
        this.dialogActionCustomClick = customClick;
    }

    boolean available() { return available; }
    String status() { return status; }
    Class<? extends Event> customClickEventClass() { return customClickEventClass; }

    Object createNativeDialog(PlatformDialog dialog, Map<String, String> buttonActionKeys) {
        if (!available) {
            throw new IllegalStateException("Native dialog API unavailable: " + status);
        }
        try {
            List<Object> body = new ArrayList<>();
            for (PlatformDialog.ItemBodySpec itemSpec : dialog.itemBodies()) {
                if (itemSpec == null || itemSpec.item() == null || itemSpec.item().getType().isAir()) {
                    continue;
                }
                if (dialogBodyItem != null) {
                    Object desc = itemSpec.description() == null || itemSpec.description().equals(Component.empty())
                            ? null
                            : dialogBodyPlainMessage.invoke(null, itemSpec.description());
                    body.add(dialogBodyItem.invoke(null, itemSpec.item(), desc,
                            itemSpec.showDecorations(), itemSpec.showTooltip(),
                            itemSpec.width(), itemSpec.height()));
                } else if (itemSpec.description() != null && !itemSpec.description().equals(Component.empty())) {
                    body.add(dialogBodyPlainMessage.invoke(null, itemSpec.description()));
                }
            }
            for (Component line : dialog.body()) {
                body.add(dialogBodyPlainMessage.invoke(null, line));
            }
            List<Object> inputs = buildInputs(dialog.inputs());
            Object afterClose = Enum.valueOf((Class<Enum>) dialogAfterActionClass.asSubclass(Enum.class), "CLOSE");
            Object base = dialogBaseCreate.invoke(null, dialog.title(), null, dialog.canCloseWithEscape(), false, afterClose, body, inputs);

            int targetWidth = dialog.nativeButtonWidth() > 0 ? dialog.nativeButtonWidth() : 150;
            for (DialogButton b : dialog.buttons()) {
                if (b.width() > targetWidth && b.width() <= 240) {
                    targetWidth = b.width();
                }
            }
            List<Object> actionButtons = new ArrayList<>();
            for (DialogButton button : dialog.buttons()) {
                String actionKey = buttonActionKeys.get(button.id());
                Object action = actionKey == null ? null : createCustomClickAction(actionKey);
                int effectiveWidth = button.width() > 0 ? button.width() : targetWidth;
                if (isCancelLike(button.id()) && effectiveWidth < targetWidth) {
                    effectiveWidth = targetWidth;
                }
                actionButtons.add(actionButtonCreate.invoke(null, button.label(), emptyToNull(button.tooltip()), effectiveWidth, action));
            }
            Object type = createType(dialog, actionButtons);
            Consumer<Object> consumer = factory -> {
                try {
                    Object entryBuilder = invokeNoArgs(factory, "empty");
                    invokeOneArg(entryBuilder, "base", dialogBaseClass, base);
                    invokeOneArg(entryBuilder, "type", dialogTypeClass, type);
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException("Could not build Paper dialog", error);
                }
            };
            return dialogCreate.invoke(null, consumer);
        } catch (Throwable error) {
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Could not create native Paper dialog", error);
        }
    }

    private List<Object> buildInputs(List<DialogInputSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        if (dialogInputClass == null) {
            throw new IllegalStateException("Native dialog inputs are unavailable");
        }
        List<Object> inputs = new ArrayList<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (DialogInputSpec spec : specs) {
            if (!keys.add(spec.key())) {
                throw new IllegalArgumentException("Duplicate dialog input: " + spec.key());
            }
            inputs.add(buildInput(spec));
        }
        return inputs;
    }

    private Object buildInput(DialogInputSpec spec) {
        try {
            Object builder;
            if (spec.type() == DialogInputType.BOOLEAN) {
                builder = invokeStaticCompatible(dialogInputClass, "bool", spec.key(), spec.label());
                invokeOneArg(builder, "initial", boolean.class, spec.initialBoolean());
                invokeOneArg(builder, "onTrue", String.class, spec.onTrue());
                invokeOneArg(builder, "onFalse", String.class, spec.onFalse());
            } else if (spec.type() == DialogInputType.NUMBER_RANGE) {
                float initial = spec.initialNumber() == null ? spec.min() : spec.initialNumber();
                if (!Float.isFinite(spec.min()) || !Float.isFinite(spec.max()) || !Float.isFinite(initial)
                        || initial < spec.min() || initial > spec.max()
                        || (spec.step() != null && (!Float.isFinite(spec.step()) || spec.step() <= 0f))) {
                    throw new IllegalArgumentException("Invalid numeric input: " + spec.key());
                }
                builder = invokeStaticCompatible(dialogInputClass, "numberRange", spec.key(), spec.label(), spec.min(), spec.max());
                invokeOneArg(builder, "initial", float.class, initial);
                if (spec.step() != null) invokeOneArg(builder, "step", float.class, spec.step());
                invokeOptional(builder, "width", int.class, spec.width());
                invokeOptional(builder, "labelFormat", String.class, spec.labelFormat());
            } else if (spec.type() == DialogInputType.SINGLE_OPTION) {
                if (spec.options().isEmpty()) {
                    throw new IllegalArgumentException("Empty option input: " + spec.key());
                }
                Class<?> entryClass = Class.forName("io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput$OptionEntry", true, dialogInputClass.getClassLoader());
                List<Object> entries = new ArrayList<>();
                java.util.Set<String> values = new java.util.HashSet<>();
                int initialOptions = 0;
                for (DialogInputSpec.Option option : spec.options()) {
                    if (!values.add(option.value()) || (option.initial() && ++initialOptions > 1)) {
                        throw new IllegalArgumentException("Ambiguous option input: " + spec.key());
                    }
                    entries.add(invokeStaticCompatible(entryClass, "create", option.value(), option.label(), option.initial()));
                }
                builder = invokeStaticCompatible(dialogInputClass, "singleOption", spec.key(), spec.label(), entries);
                invokeOptional(builder, "width", int.class, spec.width());
                invokeOptional(builder, "labelVisible", boolean.class, spec.labelVisible());
            } else {
                if (spec.initial().codePointCount(0, spec.initial().length()) > spec.maxLength()) {
                    throw new IllegalArgumentException("Initial text exceeds max length: " + spec.key());
                }
                builder = invokeStaticCompatible(dialogInputClass, "text", spec.key(), spec.label());
                invokeOneArg(builder, "initial", String.class, spec.initial());
                invokeOneArg(builder, "maxLength", int.class, spec.maxLength());
                invokeOptional(builder, "width", int.class, spec.width());
                invokeOptional(builder, "labelVisible", boolean.class, spec.labelVisible());
            }
            return invokeNoArgs(builder, "build");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not build native input: " + spec.key(), error);
        }
    }

    Map<String, String> eventInputs(Event event, List<DialogInputSpec> specs) {
        Map<String, String> values = new LinkedHashMap<>();
        if (event == null || specs == null || specs.isEmpty()) {
            return values;
        }
        try {
            Object view = invokeNoArgs(event, "getDialogResponseView");
            if (view == null) {
                return values;
            }
            for (DialogInputSpec spec : specs) {
                String value = readInputValue(view, spec);
                if (value != null) {
                    values.put(spec.key(), value);
                }
            }
        } catch (Throwable ignored) {
        }
        return values;
    }

    private String readInputValue(Object view, DialogInputSpec spec) {
        try {
            Object value = switch (spec.type()) {
                case BOOLEAN -> invokeNamed(view, "getBoolean", spec.key());
                case NUMBER_RANGE -> invokeNamed(view, "getFloat", spec.key());
                case TEXT, SINGLE_OPTION -> invokeNamed(view, "getText", spec.key());
            };
            if (value instanceof Optional<?> optional) {
                value = optional.orElse(null);
            }
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    void show(Player player, Object nativeDialog) {
        if (player == null || nativeDialog == null) {
            return;
        }
        Method method = findCompatibleMethod(player.getClass(), "showDialog", nativeDialog.getClass());
        if (method == null) {
            throw new IllegalStateException("Audience#showDialog(DialogLike) not found on runtime player");
        }
        try {
            method.invoke(player, nativeDialog);
        } catch (Throwable error) {
            throw new IllegalStateException("Could not show native dialog", error);
        }
    }

    void close(Player player) {
        if (player == null) {
            return;
        }
        try {
            Method method = findNoArgMethod(player.getClass(), "closeDialog");
            if (method != null) {
                method.invoke(player);
                return;
            }
        } catch (Throwable ignored) {
        }
        player.closeInventory();
    }

    String eventIdentifier(Event event) {
        try {
            Object key = invokeNoArgs(event, "getIdentifier");
            return keyString(key);
        } catch (Throwable ignored) {
        }
        try {
            Object key = invokeNoArgs(event, "identifier");
            return keyString(key);
        } catch (Throwable ignored) {
            return "";
        }
    }

    Player eventPlayer(Event event) {
        try {
            Object player = invokeNoArgs(event, "getPlayer");
            if (player instanceof Player p) {
                return p;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object connection = invokeNoArgs(event, "getCommonConnection");
            if (connection == null) {
                return null;
            }
            Method method = findNoArgMethod(connection.getClass(), "getPlayer");
            Object player = method == null ? null : method.invoke(connection);
            return player instanceof Player p ? p : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object createCustomClickAction(String keyString) throws ReflectiveOperationException {
        String[] split = keyString.split(":", 2);
        Key key = split.length == 2 ? Key.key(split[0], split[1]) : Key.key("ledat", split[0]);
        return dialogActionCustomClick.invoke(null, key, null);
    }

    private Object createType(PlatformDialog dialog, List<Object> buttons) throws ReflectiveOperationException {
        if (buttons.isEmpty()) {
            return dialogTypeNotice.invoke(null);
        }
        if (buttons.size() == 1) {
            return dialogTypeNoticeWithButton.invoke(null, buttons.get(0));
        }
        if (buttons.size() == 2) {
            List<DialogButton> rawButtons = dialog.buttons();
            if (rawButtons.size() == 2 && isCancelLike(rawButtons.get(0).id()) && !isCancelLike(rawButtons.get(1).id())) {
                return dialogTypeMultiAction.invoke(null, buttons, null, 2);
            }
            return dialogTypeConfirmation.invoke(null, buttons.get(0), buttons.get(1));
        }
        int columns = Math.max(1, Math.min(4, dialog.nativeColumns()));
        return dialogTypeMultiAction.invoke(null, buttons, null, columns);
    }

    private static boolean isCancelLike(String id) {
        if (id == null) return false;
        return switch (id.toLowerCase(java.util.Locale.ROOT)) {
            case "cancel", "no", "deny", "back", "close" -> true;
            default -> false;
        };
    }

    private static Method findCustomClick(Class<?> dialogActionClass) {
        for (Method method : dialogActionClass.getMethods()) {
            if (!method.getName().equals("customClick") || method.getParameterCount() != 2) {
                continue;
            }
            Class<?> first = method.getParameterTypes()[0];
            if (first.isAssignableFrom(Key.class) || Key.class.isAssignableFrom(first)) {
                return method;
            }
        }
        return null;
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Class<?> argumentType) {
        Method fallback = null;
        for (Class<?> candidate : hierarchy(type)) {
            for (Method method : candidate.getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 1) {
                    continue;
                }
                if (argumentType == null || wrap(method.getParameterTypes()[0]).isAssignableFrom(wrap(argumentType))) {
                    if (java.lang.reflect.Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                        return method;
                    }
                    if (fallback == null) {
                        fallback = method;
                    }
                }
            }
        }
        if (fallback != null) {
            fallback.trySetAccessible();
        }
        return fallback;
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        Method fallback = null;
        for (Class<?> candidate : hierarchy(type)) {
            for (Method method : candidate.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) {
                    if (java.lang.reflect.Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                        return method;
                    }
                    if (fallback == null) {
                        fallback = method;
                    }
                }
            }
        }
        if (fallback != null) {
            fallback.trySetAccessible();
        }
        return fallback;
    }

    private static List<Class<?>> hierarchy(Class<?> root) {
        List<Class<?>> result = new ArrayList<>();
        if (root == null) {
            return result;
        }
        java.util.Queue<Class<?>> queue = new java.util.ArrayDeque<>();
        java.util.Set<Class<?>> visited = new java.util.LinkedHashSet<>();
        // Prefer public interfaces first, then public classes, then remaining classes
        for (Class<?> cur = root; cur != null; cur = cur.getSuperclass()) {
            for (Class<?> iface : cur.getInterfaces()) {
                if (visited.add(iface)) {
                    queue.add(iface);
                }
            }
        }
        while (!queue.isEmpty()) {
            Class<?> iface = queue.poll();
            result.add(iface);
            for (Class<?> superIface : iface.getInterfaces()) {
                if (visited.add(superIface)) {
                    queue.add(superIface);
                }
            }
        }
        for (Class<?> cur = root; cur != null; cur = cur.getSuperclass()) {
            if (visited.add(cur)) {
                result.add(cur);
            }
        }
        return result;
    }

    private static Object invokeNoArgs(Object target, String name) throws ReflectiveOperationException {
        Method method = findNoArgMethod(target.getClass(), name);
        if (method == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "#" + name + "()");
        }
        return method.invoke(target);
    }

    private static void invokeOneArg(Object target, String name, Class<?> argType, Object arg) throws ReflectiveOperationException {
        Method method = findCompatibleMethod(target.getClass(), name, argType);
        if (method == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "#" + name + "(" + argType.getSimpleName() + ")");
        }
        method.invoke(target, arg);
    }

    private static void invokeOptional(Object target, String name, Class<?> argType, Object arg) {
        try {
            invokeOneArg(target, name, argType, arg);
        } catch (Throwable ignored) {
        }
    }

    private static Object invokeNamed(Object target, String methodName, String key) throws ReflectiveOperationException {
        Method method = findCompatibleMethod(target.getClass(), methodName, String.class);
        if (method == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "#" + methodName + "(String)");
        }
        return method.invoke(target, key);
    }

    private static Object invokeStaticCompatible(Class<?> type, String name, Object... args) throws ReflectiveOperationException {
        outer:
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                Object arg = args[i];
                if (arg != null && !wrap(params[i]).isAssignableFrom(wrap(arg.getClass()))) {
                    continue outer;
                }
            }
            return method.invoke(null, args);
        }
        throw new NoSuchMethodException(type.getName() + "#" + name + "(" + args.length + ")");
    }

    private static Component emptyToNull(Component component) {
        return component == null || component.equals(Component.empty()) ? null : component;
    }

    private static String keyString(Object key) {
        if (key == null) {
            return "";
        }
        try {
            Object value = key.getClass().getMethod("asString").invoke(key);
            return String.valueOf(value);
        } catch (Throwable ignored) {
            return String.valueOf(key);
        }
    }

    private static Class<?> wrap(Class<?> type) {
        if (type == null || !type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
