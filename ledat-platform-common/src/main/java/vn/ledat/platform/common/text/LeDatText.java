package vn.ledat.platform.common.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared text helpers for item names/lore and admin messages.
 *
 * <p>Important Minecraft quirk: item display names and lore are rendered as
 * italic by the client when the JSON text does not explicitly set
 * {@code italic:false}. These helpers force the decoration recursively so GUI
 * items do not become italic accidentally.</p>
 */
public final class LeDatText {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern LEGACY_HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern LEGACY_TOKEN_PATTERN = Pattern.compile("(?i)(?:&[#0-9a-fk-or]|&#[0-9a-f]{3,5}(?![0-9a-f])|&#[k-or]|&x(?:&[0-9a-f]){6})");
    private static final Pattern MINI_TAG_PATTERN = Pattern.compile("<(/?)([A-Za-z0-9_-]+)(:[^>]*)?>");

    private LeDatText() {
    }

    public static Component smart(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty().decoration(TextDecoration.ITALIC, false);
        }
        try {
            if (input.indexOf('§') >= 0) {
                input = input.replace('§', '&');
            }
            if (input.indexOf('&') >= 0) {
                if (input.indexOf('<') >= 0 && LEGACY_TOKEN_PATTERN.matcher(input).find()) {
                    return noItalic(MINI.deserialize(escapeUnknownTags(legacyAmpersandToMiniMessage(input))));
                }
                if (input.indexOf('<') < 0) {
                    return noItalic(LEGACY_AMPERSAND.deserialize(toLegacyAmpersandHex(input)));
                }
            }
            return noItalic(MINI.deserialize(escapeUnknownTags(input)));
        } catch (Throwable ignored) {
            return noItalic(Component.text(stripFormatting(input)));
        }
    }

    public static Component legacyAmpersand(String input) {
        return smart(input == null ? "" : input.replace('\u00A7', '&'));
    }

    public static String legacySectionString(String input) {
        return LEGACY_SECTION.serialize(smart(input));
    }

    public static String legacyAmpersandString(String input) {
        return LEGACY_AMPERSAND.serialize(smart(input));
    }

    public static Component itemName(String input) {
        return smart(input);
    }

    public static List<Component> itemLore(Collection<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<Component> output = new ArrayList<>();
        for (String line : lines) {
            output.add(smart(line));
        }
        return output;
    }

    public static Component noItalic(Component component) {
        if (component == null) {
            return Component.empty().decoration(TextDecoration.ITALIC, false);
        }
        Component fixed = component.decoration(TextDecoration.ITALIC, false);
        List<Component> children = fixed.children();
        if (children.isEmpty()) {
            return fixed;
        }
        List<Component> fixedChildren = new ArrayList<>(children.size());
        for (Component child : children) {
            fixedChildren.add(noItalic(child));
        }
        return fixed.children(fixedChildren);
    }

    public static String legacyAmpersandToMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder output = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (current == '&' && i + 1 < input.length()) {
                char next = Character.toLowerCase(input.charAt(i + 1));
                if (next == '#' && i + 2 < input.length()) {
                    char malformedFormat = Character.toLowerCase(input.charAt(i + 2));
                    String malformedTag = legacyCodeToMiniTag(malformedFormat);
                    if (malformedTag != null && !Character.toString(malformedFormat).matches("[0-9a-f]")) {
                        // Some legacy configs used malformed tokens like &#l after a
                        // hex color (for example &#FFD900&#l). Treat them as the
                        // intended formatting code instead of rendering raw text.
                        output.append(malformedTag);
                        i += 2;
                        continue;
                    }
                    if (i + 7 < input.length()) {
                        String hex = input.substring(i + 2, i + 8);
                        if (hex.matches("[0-9A-Fa-f]{6}")) {
                            output.append("<#").append(hex.toUpperCase()).append('>');
                            i += 7;
                            continue;
                        }
                    }
                    Matcher shortHex = Pattern.compile("(?i)&#([0-9a-f]{3,5})(?![0-9a-f])").matcher(input.substring(i));
                    if (shortHex.lookingAt()) {
                        String hex = shortHex.group(1).toUpperCase();
                        String expanded;
                        if (hex.length() == 3) {
                            expanded = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
                        } else {
                            expanded = (hex + String.valueOf(hex.charAt(hex.length() - 1)).repeat(6)).substring(0, 6);
                        }
                        output.append("<#").append(expanded).append('>');
                        i += 1 + hex.length();
                        continue;
                    }
                }
                if (next == 'x' && i + 13 < input.length()) {
                    String sequence = input.substring(i, i + 14);
                    if (sequence.matches("(?i)&x(?:&[0-9a-f]){6}")) {
                        output.append("<#");
                        for (int j = 3; j < sequence.length(); j += 2) {
                            output.append(Character.toUpperCase(sequence.charAt(j)));
                        }
                        output.append('>');
                        i += 13;
                        continue;
                    }
                }
                String tag = legacyCodeToMiniTag(next);
                if (tag != null) {
                    output.append(tag);
                    i++;
                    continue;
                }
            }
            output.append(current);
        }
        return output.toString();
    }

    private static String legacyCodeToMiniTag(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> null;
        };
    }

    private static String escapeUnknownTags(String input) {
        if (input == null || input.isEmpty() || input.indexOf('<') < 0) {
            return input == null ? "" : input;
        }
        Matcher matcher = MINI_TAG_PATTERN.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String slash = matcher.group(1);
            String name = matcher.group(2);
            String tail = matcher.group(3);
            String raw = matcher.group(0);
            if (isKnownMiniMessageTag(name) || name.startsWith("#")) {
                matcher.appendReplacement(output, Matcher.quoteReplacement(raw));
                continue;
            }
            // LeDat legacy configs commonly use placeholders such as <player>,
            // <crate>, <keys>, <amount>. Escape unknown tags so MiniMessage
            // renders them literally until the owning plugin replaces them.
            matcher.appendReplacement(output, Matcher.quoteReplacement("\\" + raw));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static boolean isKnownMiniMessageTag(String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            return false;
        }
        String name = rawName.toLowerCase();
        return switch (name) {
            case "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
                    "gold", "gray", "grey", "dark_gray", "dark_grey", "blue", "green", "aqua",
                    "red", "light_purple", "yellow", "white",
                    "bold", "b", "italic", "i", "em", "underlined", "u",
                    "strikethrough", "st", "obfuscated", "obf", "reset",
                    "click", "hover", "show_text", "show_item", "show_entity", "insert",
                    "keybind", "lang", "lang_or", "translate", "translatable", "fallback",
                    "selector", "score", "nbt", "font", "gradient", "rainbow", "transition",
                    "newline", "br", "pride" -> true;
            default -> false;
        };
    }

    private static String toLegacyAmpersandHex(String input) {
        Matcher matcher = LEGACY_HEX_PATTERN.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("&x");
            for (char ch : hex.toCharArray()) {
                replacement.append('&').append(ch);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static String stripFormatting(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return input.replaceAll("(?i)\u00A7[0-9a-fk-orx]", "")
                .replaceAll("(?i)&[0-9a-fk-orx]", "")
                .replace("<", "")
                .replace(">", "");
    }
}
