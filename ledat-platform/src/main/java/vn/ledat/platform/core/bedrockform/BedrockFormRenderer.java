package vn.ledat.platform.core.bedrockform;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.bedrockform.BedrockForm;
import vn.ledat.platform.api.bedrockform.BedrockFormButton;
import vn.ledat.platform.api.bedrockform.BedrockFormDisplayMode;
import vn.ledat.platform.api.bedrockform.BedrockFormInput;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class BedrockFormRenderer {
    private final Plugin platformPlugin;
    private final BedrockTextRenderer textRenderer;
    private final AtomicLong blankIdFallbacks = new AtomicLong();

    public BedrockFormRenderer(Plugin platformPlugin) {
        this.platformPlugin = platformPlugin;
        this.textRenderer = new BedrockTextRenderer(platformPlugin);
    }

    public RenderedBedrockForm render(BedrockForm form) {
        int maxTitle = Math.max(16, platformPlugin.getConfig().getInt("forms.text.max-title-length", 64));
        int maxContent = Math.max(128, platformPlugin.getConfig().getInt("forms.text.max-content-length", 2048));
        boolean stripMini = platformPlugin.getConfig().getBoolean("forms.text.strip-minimessage-tags", true);
        String title = clamp(nonBlankId(plain(form.title()), form.menuId()), maxTitle);
        String content = clamp(plain(form.content()), maxContent);
        List<RenderedBedrockForm.RenderedButton> buttons = new ArrayList<>();
        for (BedrockFormButton button : form.buttons()) {
            String label = nonBlankId(plain(button.label()), button.id());
            buttons.add(new RenderedBedrockForm.RenderedButton(button.id(), clamp(label, maxTitle)));
        }
        List<RenderedBedrockForm.RenderedInput> inputs = new ArrayList<>();
        for (BedrockFormInput input : form.inputs()) {
            List<String> options = input.options().stream()
                    .map(value -> clamp(nonBlank(stripMini ? textRenderer.renderMiniMessage(value) : value, "-"), maxTitle))
                    .toList();
            inputs.add(new RenderedBedrockForm.RenderedInput(
                    input.key(),
                    input.type(),
                    clamp(nonBlankId(plain(input.label()), input.key()), maxTitle),
                    clamp(input.placeholder(), maxTitle),
                    input.initial(),
                    input.initialBoolean(),
                    options,
                    input.defaultOption(),
                    input.min(),
                    input.max(),
                    input.step(),
                    input.initialNumber()
            ));
        }
        BedrockFormDisplayMode mode = form.displayMode() == BedrockFormDisplayMode.AUTO ? BedrockFormDisplayMode.SIMPLE : form.displayMode();
        return new RenderedBedrockForm(mode, title, content, List.copyOf(buttons), List.copyOf(inputs));
    }

    public String plain(Component component) {
        return textRenderer.render(component);
    }

    private String nonBlank(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        if (!text.isEmpty()) {
            return text;
        }
        String safeFallback = fallback == null ? "" : fallback.trim();
        return safeFallback.isEmpty() ? "-" : safeFallback;
    }

    private String nonBlankId(String value, String fallback) {
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        blankIdFallbacks.incrementAndGet();
        return nonBlank(value, fallback);
    }

    ProjectionStats projectionStats() {
        BedrockTextRenderer.ProjectionStats text = textRenderer.stats();
        return new ProjectionStats(text.objectFallbacks(), blankIdFallbacks.get(), text.projectionFailures());
    }

    private String clamp(String value, int max) {
        return textRenderer.clamp(value, max);
    }

    record ProjectionStats(long objectFallbacks, long blankIdFallbacks, long failures) {
    }
}
