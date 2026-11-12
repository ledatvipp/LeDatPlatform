package vn.ledat.platform.core.bedrockform;

import vn.ledat.platform.api.bedrockform.BedrockFormDisplayMode;
import vn.ledat.platform.api.bedrockform.BedrockFormInputType;

import java.util.List;

record RenderedBedrockForm(
        BedrockFormDisplayMode mode,
        String title,
        String content,
        List<RenderedButton> buttons,
        List<RenderedInput> inputs
) {
    record RenderedButton(String id, String label) {
    }

    record RenderedInput(
            String key,
            BedrockFormInputType type,
            String label,
            String placeholder,
            String initial,
            boolean initialBoolean,
            List<String> options,
            int defaultOption,
            float min,
            float max,
            float step,
            float initialNumber
    ) {
    }
}
