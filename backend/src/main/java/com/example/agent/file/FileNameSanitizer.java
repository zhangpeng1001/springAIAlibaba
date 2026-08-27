package com.example.agent.file;

import java.text.Normalizer;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 输出目录和 Markdown 文件名的最后一道安全校验。
 * LLM 生成的标题不能直接作为路径，以防 Windows 保留字符、路径穿越或意外覆盖。
 */
@Component
public class FileNameSanitizer {
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "LPT1", "LPT2", "LPT3");

    /** 生成可用文件名；非法或空标题回退到 caller 指定的默认值。 */
    public String sanitize(String raw, String fallback) {
        String value = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFKC)
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replace("..", " ").replaceAll("\\s+", " ").trim();
        if (value.isBlank()) value = fallback;
        if (value.length() > 60) value = value.substring(0, 60).trim();
        if (RESERVED.contains(value.toUpperCase(java.util.Locale.ROOT))) value = fallback;
        return value;
    }
}
