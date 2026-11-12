package vn.ledat.platform.core.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import vn.ledat.platform.api.gui.GuiItemDefinition.HeadSource;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Validates texture references only; never opens a connection or follows a redirect. */
final class GuiHeadTexture {
    private static final Pattern HASH = Pattern.compile("[a-fA-F0-9]{32,64}");
    private static final int MAX_ENCODED_BYTES = 16384;
    private static final int MAX_DECODED_BYTES = 8192;

    private GuiHeadTexture() { }

    static Optional<URL> normalize(HeadSource source, String value) {
        if (source == null || value == null || value.length() > MAX_ENCODED_BYTES) return Optional.empty();
        try {
            String candidate = value.trim();
            if (source == HeadSource.TEXTURE_HASH) {
                if (!HASH.matcher(candidate).matches()) return Optional.empty();
                candidate = "https://textures.minecraft.net/texture/" + candidate;
            } else if (source == HeadSource.TEXTURE_BASE64) {
                byte[] decoded = Base64.getDecoder().decode(candidate);
                if (decoded.length > MAX_DECODED_BYTES) return Optional.empty();
                String json = new String(decoded, StandardCharsets.UTF_8);
                if (!boundedJson(json)) return Optional.empty();
                JsonElement root = JsonParser.parseString(json);
                JsonElement url = root.getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN").get("url");
                if (!url.isJsonPrimitive() || !url.getAsJsonPrimitive().isString()) return Optional.empty();
                candidate = url.getAsString();
            } else if (source != HeadSource.TEXTURE_URL) {
                return Optional.empty();
            }
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || !"textures.minecraft.net".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || uri.getQuery() != null || uri.getFragment() != null) return Optional.empty();
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/texture/")) return Optional.empty();
            String hash = path.substring("/texture/".length());
            if (!HASH.matcher(hash).matches()) return Optional.empty();
            return Optional.of(URI.create("https://textures.minecraft.net/texture/" + hash.toLowerCase(Locale.ROOT)).toURL());
        } catch (RuntimeException | java.net.MalformedURLException error) {
            return Optional.empty();
        }
    }

    private static boolean boundedJson(String json) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < json.length(); index++) {
            char character = json.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
            } else if (character == '"') quoted = true;
            else if (character == '{' || character == '[') {
                if (++depth > 16) return false;
            } else if (character == '}' || character == ']') {
                if (--depth < 0) return false;
            }
        }
        return depth == 0 && !quoted;
    }
}
