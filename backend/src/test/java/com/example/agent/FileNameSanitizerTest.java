package com.example.agent;

import com.example.agent.file.FileNameSanitizer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 文件名安全测试覆盖路径穿越、Windows 保留名称和非法字符。 */
class FileNameSanitizerTest {
    private final FileNameSanitizer sanitizer = new FileNameSanitizer();

    @Test
    void removesTraversalAndReservedCharacters() {
        String value = sanitizer.sanitize("../CON:<Java>?", "fallback");
        assertFalse(value.contains(".."));
        assertFalse(value.matches(".*[\\\\/:*?\"<>|].*"));
    }

    @Test
    void fallsBackForReservedDeviceName() {
        assertEquals("fallback", sanitizer.sanitize("CON", "fallback"));
    }
}
