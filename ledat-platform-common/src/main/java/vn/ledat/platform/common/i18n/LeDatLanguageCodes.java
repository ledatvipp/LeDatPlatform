package vn.ledat.platform.common.i18n;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared language code normalization for the LeDat suite.
 * Canonical file names are vi_VN.yml, en_US.yml and zh_CN.yml, but legacy aliases
 * such as vi, vn, en, zh, cn, lang_vi, lang_en and lang_zh are still accepted.
 */
public final class LeDatLanguageCodes {
    public static final String VI_VN = "vi_VN";
    public static final String EN_US = "en_US";
    public static final String ZH_CN = "zh_CN";
    public static final String DEFAULT = VI_VN;

    private static final List<String> CANONICAL = List.of(VI_VN, EN_US, ZH_CN);
    private static final Set<String> CANONICAL_SET = Set.copyOf(CANONICAL);
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        alias(VI_VN, "vi", "vn", "vi_vn", "vi-vn", "vietnam", "vietnamese", "tieng_viet", "tiếng_việt", "lang_vi", "lang_vn");
        alias(EN_US, "en", "us", "en_us", "en-us", "english", "lang_en", "lang_us");
        alias(ZH_CN, "zh", "cn", "zh_cn", "zh-cn", "chinese", "simplified_chinese", "simplified-chinese", "mandarin", "中文", "简体中文", "lang_zh", "lang_cn");
    }

    private LeDatLanguageCodes() {
    }

    private static void alias(String canonical, String... aliases) {
        ALIASES.put(key(canonical), canonical);
        for (String alias : aliases) {
            ALIASES.put(key(alias), canonical);
        }
    }

    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return DEFAULT;
        }
        String trimmed = input.trim();
        return ALIASES.getOrDefault(key(trimmed), trimmed);
    }

    public static boolean isSupported(String input) {
        return CANONICAL_SET.contains(normalize(input));
    }

    public static String normalizeSupported(String input, String fallback) {
        String normalized = normalize(input);
        if (CANONICAL_SET.contains(normalized)) {
            return normalized;
        }
        String normalizedFallback = normalize(fallback);
        return CANONICAL_SET.contains(normalizedFallback) ? normalizedFallback : DEFAULT;
    }

    public static String legacyShort(String input) {
        String normalized = normalize(input);
        if (EN_US.equals(normalized)) {
            return "en";
        }
        if (ZH_CN.equals(normalized)) {
            return "zh";
        }
        return "vi";
    }

    public static String displayName(String input) {
        String normalized = normalize(input);
        return switch (normalized) {
            case EN_US -> "English";
            case ZH_CN -> "简体中文";
            case VI_VN -> "Tiếng Việt";
            default -> normalized;
        };
    }

    public static List<String> canonicalLanguages() {
        return CANONICAL;
    }

    public static String next(String input) {
        String normalized = normalizeSupported(input, DEFAULT);
        int index = CANONICAL.indexOf(normalized);
        return CANONICAL.get((index + 1) % CANONICAL.size());
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
