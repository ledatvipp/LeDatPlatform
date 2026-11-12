package vn.ledat.platform.core.bedrockform;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.bedrockform.BedrockForm;
import vn.ledat.platform.api.bedrockform.BedrockFormButton;
import vn.ledat.platform.api.bedrockform.BedrockFormClick;
import vn.ledat.platform.api.bedrockform.BedrockFormDisplayMode;
import vn.ledat.platform.api.bedrockform.BedrockFormInput;
import vn.ledat.platform.api.bedrockform.BedrockFormService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class CustomBedrockForm extends AbstractBedrockFormBuilder implements BedrockFormService.CustomBuilder {
    private final List<BedrockFormInput> inputs = new ArrayList<>();
    private BedrockFormButton submit = new BedrockFormButton("submit", Component.text("Submit"), click -> { });

    CustomBedrockForm(DefaultBedrockFormService service, Plugin plugin, String menuId) {
        super(service, plugin, menuId);
    }

    @Override
    public CustomBedrockForm title(Component title) {
        super.title(title);
        return this;
    }

    @Override
    public CustomBedrockForm content(Component content) {
        if (content != null) {
            inputs.add(BedrockFormInput.label(content));
        }
        super.content(content);
        return this;
    }

    @Override
    public CustomBedrockForm onClose(Consumer<vn.ledat.platform.api.bedrockform.BedrockFormClose> handler) {
        super.onClose(handler);
        return this;
    }

    @Override
    public CustomBedrockForm context(String key, Object value) {
        super.context(key, value);
        return this;
    }

    @Override
    public CustomBedrockForm label(Component label) {
        inputs.add(BedrockFormInput.label(label));
        return this;
    }

    @Override
    public CustomBedrockForm input(String key, Component label, String placeholder, String initial) {
        inputs.add(BedrockFormInput.text(key, label, placeholder, initial));
        return this;
    }

    @Override
    public CustomBedrockForm toggle(String key, Component label, boolean initial) {
        inputs.add(BedrockFormInput.toggle(key, label, initial));
        return this;
    }

    @Override
    public CustomBedrockForm dropdown(String key, Component label, List<String> options, int defaultOption) {
        inputs.add(BedrockFormInput.dropdown(key, label, options, defaultOption));
        return this;
    }

    @Override
    public CustomBedrockForm slider(String key, Component label, float min, float max, float step, float initial) {
        inputs.add(BedrockFormInput.slider(key, label, min, max, step, initial));
        return this;
    }

    @Override
    public CustomBedrockForm stepSlider(String key, Component label, List<String> steps, int defaultStep) {
        inputs.add(BedrockFormInput.stepSlider(key, label, steps, defaultStep));
        return this;
    }

    @Override
    public CustomBedrockForm submit(Component label, Consumer<BedrockFormClick> action) {
        this.submit = new BedrockFormButton("submit", label, action);
        return this;
    }

    @Override
    public BedrockForm build() {
        return new BedrockForm(plugin, menuId, BedrockFormDisplayMode.CUSTOM, title, content, List.of(submit), inputs, context, closeHandler);
    }
}
