package vn.ledat.platform.core.dialog;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import vn.ledat.platform.api.dialog.DialogInputSpec;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultDialogInputTemplateTest {
    @Test
    void acceptsIntegerAndResolvedNumericAndBooleanInitials() throws Exception {
        try (DialogTestSupport f = new DialogTestSupport()) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString("""
                    inputs:
                      duration:
                        type: NUMBER_RANGE
                        min: 2
                        max: 60
                        step: 1
                        initial-number: '{duration}'
                      enabled:
                        type: BOOLEAN
                        initial-boolean: '{enabled}'
                      kind:
                        type: SINGLE_OPTION
                        options:
                          first:
                            value: original-a
                            label: '<aqua>Localized A'
                          second:
                            value: original-b
                            label: '<yellow>Localized B'
                            initial: '{selected}'
                    """);
            List<DialogInputSpec> inputs = parse(f.dialogService(), yaml, Map.of("duration", "5", "enabled", "true", "selected", "true"));
            assertEquals(2f, inputs.get(0).min());
            assertEquals(60f, inputs.get(0).max());
            assertEquals(1f, inputs.get(0).step());
            assertEquals(5f, inputs.get(0).initialNumber());
            assertTrue(inputs.get(1).initialBoolean());
            assertTrue(inputs.get(2).options().get(1).initial());
            assertEquals("original-b", inputs.get(2).options().get(1).value());
        }
    }

    @Test
    void acceptsDecimalYamlWithoutChangingItsValue() throws Exception {
        try (DialogTestSupport f = new DialogTestSupport()) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString("inputs:\n  volume:\n    type: NUMBER_RANGE\n    min: 0.5\n    max: 2.5\n    step: 0.25\n    initial-number: 1.25\n");
            DialogInputSpec input = parse(f.dialogService(), yaml, Map.of()).getFirst();
            assertEquals(0.5f, input.min());
            assertEquals(2.5f, input.max());
            assertEquals(0.25f, input.step());
            assertEquals(1.25f, input.initialNumber());
        }
    }

    @Test
    void rejectsMalformedNonfiniteAndNonpositiveInitialScalars() throws Exception {
        try (DialogTestSupport f = new DialogTestSupport()) {
            DefaultDialogService service = f.dialogService();
            for (String value : List.of("NaN", "Infinity", "{unresolved}", "wrong")) {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.set("inputs.number.type", "NUMBER_RANGE");
                yaml.set("inputs.number.initial-number", value);
                assertThrows(IllegalArgumentException.class, () -> parse(service, yaml, Map.of()), value);
            }
            for (Number step : List.of(0, -1, Double.NaN)) {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.set("inputs.number.step", step);
                assertThrows(IllegalArgumentException.class, () -> parse(service, yaml, Map.of()));
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("inputs.enabled.initial-boolean", "{unresolved}");
            assertThrows(IllegalArgumentException.class, () -> parse(service, yaml, Map.of()));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<DialogInputSpec> parse(DefaultDialogService service, YamlConfiguration yaml, Map<String, String> placeholders) throws Exception {
        return (List<DialogInputSpec>) DialogTestSupport.call(service, "parseInputs", new Class<?>[]{ConfigurationSection.class, Map.class}, yaml.getConfigurationSection("inputs"), placeholders);
    }
}
