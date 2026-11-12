package vn.ledat.platform.api.bedrockform;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public final class BedrockForm {
    private final Plugin plugin;
    private final String menuId;
    private final BedrockFormDisplayMode displayMode;
    private final Component title;
    private final Component content;
    private final List<BedrockFormButton> buttons;
    private final List<BedrockFormInput> inputs;
    private final Map<String, Object> context;
    private final Consumer<BedrockFormClose> closeHandler;

    public BedrockForm(Plugin plugin, String menuId, BedrockFormDisplayMode displayMode, Component title, Component content,
                       List<BedrockFormButton> buttons, List<BedrockFormInput> inputs, Map<String, Object> context,
                       Consumer<BedrockFormClose> closeHandler) {
        this.plugin = plugin;
        this.menuId = sanitize(menuId);
        this.displayMode = displayMode == null ? BedrockFormDisplayMode.SIMPLE : displayMode;
        this.title = title == null ? Component.text(this.menuId) : title;
        this.content = content == null ? Component.empty() : content;
        this.buttons = buttons == null ? List.of() : List.copyOf(buttons);
        this.inputs = inputs == null ? List.of() : List.copyOf(inputs);
        this.context = context == null ? Map.of() : Map.copyOf(context);
        this.closeHandler = closeHandler == null ? close -> { } : closeHandler;
    }

    public Plugin plugin() { return plugin; }
    public String menuId() { return menuId; }
    public BedrockFormDisplayMode displayMode() { return displayMode; }
    public Component title() { return title; }
    public Component content() { return content; }
    public List<BedrockFormButton> buttons() { return buttons; }
    public List<BedrockFormInput> inputs() { return inputs; }
    public Map<String, Object> context() { return context; }
    public Consumer<BedrockFormClose> closeHandler() { return closeHandler; }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "form";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.:-]", "_");
    }
}
