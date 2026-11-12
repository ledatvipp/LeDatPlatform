package vn.ledat.platform.core.bedrockform;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.bedrockform.BedrockForm;
import vn.ledat.platform.api.bedrockform.BedrockFormButton;
import vn.ledat.platform.api.bedrockform.BedrockFormClick;
import vn.ledat.platform.api.bedrockform.BedrockFormDisplayMode;
import vn.ledat.platform.api.bedrockform.BedrockFormService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class SimpleBedrockForm extends AbstractBedrockFormBuilder implements BedrockFormService.SimpleBuilder {
    private final List<BedrockFormButton> buttons = new ArrayList<>();

    SimpleBedrockForm(DefaultBedrockFormService service, Plugin plugin, String menuId) {
        super(service, plugin, menuId);
    }

    @Override
    public SimpleBedrockForm title(Component title) {
        super.title(title);
        return this;
    }

    @Override
    public SimpleBedrockForm content(Component content) {
        super.content(content);
        return this;
    }

    @Override
    public SimpleBedrockForm onClose(Consumer<vn.ledat.platform.api.bedrockform.BedrockFormClose> handler) {
        super.onClose(handler);
        return this;
    }

    @Override
    public SimpleBedrockForm context(String key, Object value) {
        super.context(key, value);
        return this;
    }

    @Override
    public SimpleBedrockForm button(String id, Component label, Consumer<BedrockFormClick> action) {
        buttons.add(new BedrockFormButton(id, label, action));
        return this;
    }

    @Override
    public BedrockForm build() {
        return new BedrockForm(plugin, menuId, BedrockFormDisplayMode.SIMPLE, title, content, buttons, List.of(), context, closeHandler);
    }
}
