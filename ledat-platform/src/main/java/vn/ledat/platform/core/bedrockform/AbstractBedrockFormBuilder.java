package vn.ledat.platform.core.bedrockform;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.bedrockform.BedrockForm;
import vn.ledat.platform.api.bedrockform.BedrockFormClose;
import vn.ledat.platform.api.bedrockform.BedrockFormResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

abstract class AbstractBedrockFormBuilder {
    protected final DefaultBedrockFormService service;
    protected final Plugin plugin;
    protected final String menuId;
    protected Component title;
    protected Component content = Component.empty();
    protected final Map<String, Object> context = new LinkedHashMap<>();
    protected Consumer<BedrockFormClose> closeHandler = close -> { };

    AbstractBedrockFormBuilder(DefaultBedrockFormService service, Plugin plugin, String menuId) {
        this.service = service;
        this.plugin = plugin;
        this.menuId = menuId;
    }

    public AbstractBedrockFormBuilder title(Component title) {
        this.title = title;
        return this;
    }

    public AbstractBedrockFormBuilder content(Component content) {
        this.content = content == null ? Component.empty() : content;
        return this;
    }

    public AbstractBedrockFormBuilder onClose(Consumer<BedrockFormClose> handler) {
        this.closeHandler = handler == null ? close -> { } : handler;
        return this;
    }

    public AbstractBedrockFormBuilder context(String key, Object value) {
        if (key == null || key.isBlank()) {
            return this;
        }
        if (value == null) {
            context.remove(key);
        } else {
            context.put(key, value);
        }
        return this;
    }

    public abstract BedrockForm build();

    public BedrockFormResult open(Player player) {
        return service.open(player, build());
    }
}
