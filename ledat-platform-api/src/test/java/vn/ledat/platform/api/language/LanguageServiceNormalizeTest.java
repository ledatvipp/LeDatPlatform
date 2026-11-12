package vn.ledat.platform.api.language;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LanguageServiceNormalizeTest {

    @Test
    void normalizesVietnameseAliases() {
        assertEquals("vi_VN", LanguageService.normalize("vi"));
        assertEquals("vi_VN", LanguageService.normalize("vn"));
        assertEquals("vi_VN", LanguageService.normalize("vi-vn"));
        assertEquals("vi_VN", LanguageService.normalize("Tiếng_Việt"));
    }

    @Test
    void normalizesEnglishAliases() {
        assertEquals("en_US", LanguageService.normalize("en"));
        assertEquals("en_US", LanguageService.normalize("english"));
        assertEquals("en_US", LanguageService.normalize("en-us"));
    }

    @Test
    void normalizesChineseAliases() {
        assertEquals("zh_CN", LanguageService.normalize("zh"));
        assertEquals("zh_CN", LanguageService.normalize("cn"));
        assertEquals("zh_CN", LanguageService.normalize("chs"));
        assertEquals("zh_CN", LanguageService.normalize("chinese"));
    }
}
