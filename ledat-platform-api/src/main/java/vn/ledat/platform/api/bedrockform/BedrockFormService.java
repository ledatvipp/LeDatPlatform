package vn.ledat.platform.api.bedrockform;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public interface BedrockFormService {
    boolean isAvailable();
    boolean isBedrock(Player player);

    default boolean canOpen(Player player) {
        return isAvailable() && isBedrock(player);
    }

    SimpleBuilder simple(Plugin plugin, String menuId);
    ModalBuilder modal(Plugin plugin, String menuId);
    CustomBuilder custom(Plugin plugin, String menuId);

    BedrockFormResult open(Player player, BedrockForm form);
    void close(Player player, boolean clearSession);
    BedrockFormSession session(UUID playerId);
    int sessionCount();
    int sessionCount(Plugin plugin);
    void cleanup(UUID playerId);
    void cleanup(Plugin plugin);
    int cleanupExpired();

    default void cleanupAll() { }

    default BedrockFormStats stats() {
        return new BedrockFormStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    default Map<String, String> diagnostics(Player player) {
        return Map.of(
                "available", String.valueOf(isAvailable()),
                "bedrock", String.valueOf(isBedrock(player)),
                "sessions", String.valueOf(sessionCount())
        );
    }

    static BedrockFormService disabled() {
        return Disabled.INSTANCE;
    }

    interface BaseBuilder<T extends BaseBuilder<T>> {
        T title(Component title);
        T content(Component content);
        T onClose(Consumer<BedrockFormClose> handler);
        T context(String key, Object value);
        BedrockForm build();
        BedrockFormResult open(Player player);
    }

    interface SimpleBuilder extends BaseBuilder<SimpleBuilder> {
        SimpleBuilder button(String id, Component label, Consumer<BedrockFormClick> action);
    }

    interface ModalBuilder extends BaseBuilder<ModalBuilder> {
        ModalBuilder confirm(Component label, Consumer<BedrockFormClick> action);
        ModalBuilder cancel(Component label, Consumer<BedrockFormClick> action);
    }

    interface CustomBuilder extends BaseBuilder<CustomBuilder> {
        CustomBuilder label(Component label);
        CustomBuilder input(String key, Component label, String placeholder, String initial);
        CustomBuilder toggle(String key, Component label, boolean initial);
        CustomBuilder dropdown(String key, Component label, List<String> options, int defaultOption);
        CustomBuilder slider(String key, Component label, float min, float max, float step, float initial);
        CustomBuilder stepSlider(String key, Component label, List<String> steps, int defaultStep);
        CustomBuilder submit(Component label, Consumer<BedrockFormClick> action);
    }

    final class Disabled implements BedrockFormService {
        private static final Disabled INSTANCE = new Disabled();

        private Disabled() {
        }

        @Override public boolean isAvailable() { return false; }
        @Override public boolean isBedrock(Player player) { return false; }
        @Override public SimpleBuilder simple(Plugin plugin, String menuId) { return new DisabledSimpleBuilder(plugin, menuId); }
        @Override public ModalBuilder modal(Plugin plugin, String menuId) { return new DisabledModalBuilder(plugin, menuId); }
        @Override public CustomBuilder custom(Plugin plugin, String menuId) { return new DisabledCustomBuilder(plugin, menuId); }
        @Override public BedrockFormResult open(Player player, BedrockForm form) { return BedrockFormResult.DISABLED; }
        @Override public void close(Player player, boolean clearSession) { }
        @Override public BedrockFormSession session(UUID playerId) { return null; }
        @Override public int sessionCount() { return 0; }
        @Override public int sessionCount(Plugin plugin) { return 0; }
        @Override public void cleanup(UUID playerId) { }
        @Override public void cleanup(Plugin plugin) { }
        @Override public int cleanupExpired() { return 0; }
    }

    abstract class DisabledBase<T extends BaseBuilder<T>> implements BaseBuilder<T> {
        protected final Plugin plugin;
        protected final String menuId;
        protected final BedrockFormDisplayMode mode;
        protected Component title;
        protected Component content;

        protected DisabledBase(Plugin plugin, String menuId, BedrockFormDisplayMode mode) {
            this.plugin = plugin;
            this.menuId = menuId;
            this.mode = mode;
        }

        @SuppressWarnings("unchecked")
        public T title(Component title) { this.title = title; return (T) this; }
        @SuppressWarnings("unchecked")
        public T content(Component content) { this.content = content; return (T) this; }
        @SuppressWarnings("unchecked")
        public T onClose(Consumer<BedrockFormClose> handler) { return (T) this; }
        @SuppressWarnings("unchecked")
        public T context(String key, Object value) { return (T) this; }
        @Override public BedrockForm build() { return new BedrockForm(plugin, menuId, mode, title, content, List.of(), List.of(), Map.of(), close -> { }); }
        @Override public BedrockFormResult open(Player player) { return BedrockFormResult.DISABLED; }
    }

    final class DisabledSimpleBuilder extends DisabledBase<SimpleBuilder> implements SimpleBuilder {
        private DisabledSimpleBuilder(Plugin plugin, String menuId) { super(plugin, menuId, BedrockFormDisplayMode.SIMPLE); }
        @Override public SimpleBuilder button(String id, Component label, Consumer<BedrockFormClick> action) { return this; }
    }

    final class DisabledModalBuilder extends DisabledBase<ModalBuilder> implements ModalBuilder {
        private DisabledModalBuilder(Plugin plugin, String menuId) { super(plugin, menuId, BedrockFormDisplayMode.MODAL); }
        @Override public ModalBuilder confirm(Component label, Consumer<BedrockFormClick> action) { return this; }
        @Override public ModalBuilder cancel(Component label, Consumer<BedrockFormClick> action) { return this; }
    }

    final class DisabledCustomBuilder extends DisabledBase<CustomBuilder> implements CustomBuilder {
        private DisabledCustomBuilder(Plugin plugin, String menuId) { super(plugin, menuId, BedrockFormDisplayMode.CUSTOM); }
        @Override public CustomBuilder label(Component label) { return this; }
        @Override public CustomBuilder input(String key, Component label, String placeholder, String initial) { return this; }
        @Override public CustomBuilder toggle(String key, Component label, boolean initial) { return this; }
        @Override public CustomBuilder dropdown(String key, Component label, List<String> options, int defaultOption) { return this; }
        @Override public CustomBuilder slider(String key, Component label, float min, float max, float step, float initial) { return this; }
        @Override public CustomBuilder stepSlider(String key, Component label, List<String> steps, int defaultStep) { return this; }
        @Override public CustomBuilder submit(Component label, Consumer<BedrockFormClick> action) { return this; }
    }
}
