package vn.ledat.platform.core.gui;

import org.junit.jupiter.api.Test;
import vn.ledat.platform.api.gui.GuiItemDefinition.HeadSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class GuiHeadTextureTest {
    private static final String HASH = "a".repeat(64);
    private static final String URL = "https://textures.minecraft.net/texture/" + HASH;

    @Test
    void urlHashAndBase64ConvergeOnOneNormalizedHttpsTextureKey() {
        String payload = Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"" + URL + "\"}}}").getBytes(StandardCharsets.UTF_8));
        assertEquals(URL, GuiHeadTexture.normalize(HeadSource.TEXTURE_URL, "http://textures.minecraft.net/texture/" + HASH.toUpperCase()).orElseThrow().toExternalForm());
        assertEquals(URL, GuiHeadTexture.normalize(HeadSource.TEXTURE_HASH, HASH).orElseThrow().toExternalForm());
        assertEquals(URL, GuiHeadTexture.normalize(HeadSource.TEXTURE_BASE64, payload).orElseThrow().toExternalForm());
        assertTrue(GuiHeadTexture.normalize(HeadSource.PLAYER, URL).isEmpty());
    }

    @Test
    void foreignHostsUserInfoPortsQueriesEncodedPathsAndMalformedPayloadsNeverBecomeTextures() {
        for (String value : new String[]{"https://example.com/texture/" + HASH,
                "https://textures.minecraft.net.evil.test/texture/" + HASH,
                "https://textures.minecraft.net@evil.test/texture/" + HASH,
                "https://textures.minecraft.net:443/texture/" + HASH,
                URL + "?redirect=evil", URL + "#fragment", "file:///texture/" + HASH,
                "https://textures.minecraft.net/texture/%61" + "a".repeat(63)}) {
            assertTrue(GuiHeadTexture.normalize(HeadSource.TEXTURE_URL, value).isEmpty(), value);
        }
        for (String raw : new String[]{"{}", "{\"textures\":[]}", "[]", "null", "[".repeat(32) + "]".repeat(32), "{\"textures\":{\"SKIN\":{\"url\":17}}}"}) {
            String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            assertTrue(GuiHeadTexture.normalize(HeadSource.TEXTURE_BASE64, encoded).isEmpty(), raw);
        }
        assertTrue(GuiHeadTexture.normalize(HeadSource.TEXTURE_BASE64, "not-base64%").isEmpty());
        assertTrue(GuiHeadTexture.normalize(HeadSource.TEXTURE_BASE64, "A".repeat(16385)).isEmpty());
        assertTrue(GuiHeadTexture.normalize(HeadSource.TEXTURE_HASH, "../" + HASH).isEmpty());
    }
}
