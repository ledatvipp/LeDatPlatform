package vn.ledat.platform.core.bedrockform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class BedrockTextRenderer {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final MiniMessage miniMessage;
    private final ObjectAltResolver objectAltResolver;
    private final String objectFallback;
    private final AtomicLong objectFallbacks = new AtomicLong();
    private final AtomicLong projectionFailures = new AtomicLong();

    public BedrockTextRenderer(Plugin plugin) {
        this(
                MiniMessage.miniMessage(),
                new ReflectiveObjectAltResolver(),
                plugin == null ? "[icon]" : plugin.getConfig().getString("forms.text.object-fallback", "[icon]")
        );
    }

    BedrockTextRenderer(MiniMessage miniMessage, ObjectAltResolver objectAltResolver, String objectFallback) {
        this.miniMessage = miniMessage == null ? MiniMessage.miniMessage() : miniMessage;
        this.objectAltResolver = objectAltResolver == null ? component -> null : objectAltResolver;
        this.objectFallback = objectFallback == null || objectFallback.isBlank() ? "[icon]" : objectFallback.trim();
    }

    public String render(Component component) {
        if (component == null) {
            return "";
        }
        StringBuilder output = new StringBuilder();
        try {
            append(component, output);
            return output.toString();
        } catch (Throwable ignored) {
            projectionFailures.incrementAndGet();
            try {
                return PLAIN.serialize(component);
            } catch (Throwable ignoredAgain) {
                return "";
            }
        }
    }

    public String renderMiniMessage(String value) {
        String text = value == null ? "" : value;
        try {
            return render(miniMessage.deserialize(text));
        } catch (Throwable ignored) {
            projectionFailures.incrementAndGet();
            return text;
        }
    }

    public String clamp(String value, int maxCodePoints) {
        String text = value == null ? "" : value;
        int limit = Math.max(0, maxCodePoints);
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= limit) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, limit));
    }

    public ProjectionStats stats() {
        return new ProjectionStats(objectFallbacks.get(), projectionFailures.get());
    }

    private void append(Component component, StringBuilder output) {
        String objectAlt = objectAltResolver.resolve(component);
        if (objectAlt != null) {
            String semantic = objectAlt.isBlank() ? objectFallback : objectAlt;
            output.append(semantic);
            objectFallbacks.incrementAndGet();
        } else {
            output.append(PLAIN.serialize(component.children(List.of())));
        }
        for (Component child : component.children()) {
            append(child, output);
        }
    }

    @FunctionalInterface
    interface ObjectAltResolver {
        String resolve(Component component);
    }

    public record ProjectionStats(long objectFallbacks, long projectionFailures) {
    }

    private static final class ReflectiveObjectAltResolver implements ObjectAltResolver {
        @Override
        public String resolve(Component component) {
            Object contents = objectContents(component);
            if (contents == null) {
                return null;
            }
            String hover = hoverText(component);
            if (!hover.isBlank()) {
                return hover;
            }
            String sprite = accessor(contents, "sprite");
            if (!sprite.isBlank()) {
                return pretty(sprite);
            }
            for (String identityAccessor : List.of("playerName", "name", "id", "uuid", "profile")) {
                String value = accessor(contents, identityAccessor);
                if (!value.isBlank()) {
                    return value;
                }
            }
            return "";
        }

        private static Object objectContents(Component component) {
            if (component == null) {
                return null;
            }
            try {
                Class<?> objectComponent = Class.forName("net.kyori.adventure.text.ObjectComponent");
                if (!objectComponent.isInstance(component)) {
                    return null;
                }
                Method method = objectComponent.getMethod("contents");
                Object contents = method.invoke(component);
                return contents;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static String hoverText(Component component) {
            try {
                Object hoverEvent = Component.class.getMethod("hoverEvent").invoke(component);
                if (hoverEvent == null) {
                    return "";
                }
                Object value = hoverEvent.getClass().getMethod("value").invoke(hoverEvent);
                return value instanceof Component hoverComponent ? PLAIN.serialize(hoverComponent).trim() : "";
            } catch (Throwable ignored) {
                return "";
            }
        }

        private static String accessor(Object target, String name) {
            for (String typeName : List.of(
                    "net.kyori.adventure.text.object.SpriteObjectContents",
                    "net.kyori.adventure.text.object.PlayerHeadObjectContents"
            )) {
                try {
                    Class<?> type = Class.forName(typeName);
                    if (!type.isInstance(target)) {
                        continue;
                    }
                    Object value = type.getMethod(name).invoke(target);
                    return value == null ? "" : String.valueOf(value).trim();
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable ignored) {
                    return "";
                }
            }
            return "";
        }

        private static String pretty(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            int namespace = normalized.indexOf(':');
            if (namespace >= 0 && namespace + 1 < normalized.length()) {
                normalized = normalized.substring(namespace + 1);
            }
            int path = normalized.lastIndexOf('/');
            if (path >= 0 && path + 1 < normalized.length()) {
                normalized = normalized.substring(path + 1);
            }
            normalized = normalized.replace('_', ' ');
            StringBuilder output = new StringBuilder();
            for (String part : normalized.split(" +")) {
                if (part.isBlank()) {
                    continue;
                }
                if (!output.isEmpty()) {
                    output.append(' ');
                }
                output.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            return output.toString();
        }
    }
}
