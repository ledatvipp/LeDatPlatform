package vn.ledat.platform.core.language;

import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BundledLanguageVersionTest {
    @Test
    void usesThePluginsOwnLocaleVersionRatherThanTheCoreSchema() throws Exception {
        assertEquals(13, version("language-version: 13\nauction: {loading: Ready}", 5));
        assertEquals(2, version("language-version: 2\nprefix: Older independent plugin", 5));
    }

    @Test
    void keepsCoreFallbackForUnversionedOrUnavailableBundles() throws Exception {
        assertEquals(5, DefaultLanguageService.bundledLanguageVersion(null, 5));
        assertEquals(5, version("prefix: Unversioned", 5));
        assertEquals(5, version("language-version: 0", 5));
    }

    @Test
    void preservesDisabledVersionChecking() throws Exception {
        assertEquals(0, version("language-version: 13", 0));
    }

    @Test
    void rejectsMalformedBundledYamlInsteadOfClaimingAnIncorrectVersion() {
        assertThrows(InvalidConfigurationException.class, () -> version("language-version: [", 5));
    }

    private static int version(String yaml, int coreVersion) throws Exception {
        try (var stream = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
            return DefaultLanguageService.bundledLanguageVersion(stream, coreVersion);
        }
    }
}
