package vn.ledat.platform.core.bedrockform;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.bedrockform.BedrockForm;
import vn.ledat.platform.api.bedrockform.BedrockFormButton;
import vn.ledat.platform.api.bedrockform.BedrockFormClick;
import vn.ledat.platform.api.bedrockform.BedrockFormDisplayMode;
import vn.ledat.platform.api.bedrockform.BedrockFormService;

import java.util.List;
import java.util.function.Consumer;

public final class ModalBedrockForm extends AbstractBedrockFormBuilder implements BedrockFormService.ModalBuilder {
    private BedrockFormButton confirm = new BedrockFormButton("confirm", Component.text("Confirm"), click -> { });
    private BedrockFormButton cancel = new BedrockFormButton("cancel", Component.text("Cancel"), click -> { });

    ModalBedrockForm(DefaultBedrockFormService service, Plugin plugin, String menuId) {
        super(service, plugin, menuId);
    }

    @Override
    public ModalBedrockForm title(Component title) {
        super.title(title);
        return this;
    }

    @Override
    public ModalBedrockForm content(Component content) {
        super.content(content);
        return this;
    }

    @Override
    public ModalBedrockForm onClose(Consumer<vn.ledat.platform.api.bedrockform.BedrockFormClose> handler) {
        super.onClose(handler);
        return this;
    }

    @Override
    public ModalBedrockForm context(String key, Object value) {
        super.context(key, value);
        return this;
    }

    @Override
    public ModalBedrockForm confirm(Component label, Consumer<BedrockFormClick> action) {
        this.confirm = new BedrockFormButton("confirm", label, action);
        return this;
    }

    @Override
    public ModalBedrockForm cancel(Component label, Consumer<BedrockFormClick> action) {
        this.cancel = new BedrockFormButton("cancel", label, action);
        return this;
    }

    @Override
    public BedrockForm build() {
        return new BedrockForm(plugin, menuId, BedrockFormDisplayMode.MODAL, title, content, List.of(confirm, cancel), List.of(), context, closeHandler);
    }
}
