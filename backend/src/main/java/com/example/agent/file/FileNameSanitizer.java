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
    /**
     * Windows 设备保留名称。即使扩展名不同也不应作为普通文件/目录名，以免创建结果在不同系统表现不一致。
     */
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "LPT1", "LPT2", "LPT3");

    /**
     * 将模型候选标题转换为单个安全文件名片段。
     *
     * <p>依次执行 Unicode NFKC 规范化、Windows 非法字符剔除、路径穿越片段剔除、空白压缩、
     * 长度限制和保留设备名回退。此方法只产生文件名片段，不产生或校验完整路径。</p>
     *
     * @param raw 模型或 PlanItem 给出的原始标题，可为空
     * @param fallback 服务端预设的安全回退名称，不应来自外部请求
     * @return 无目录分隔符、无控制字符且长度受限的文件名片段
     */
    public String sanitize(String raw, String fallback) {
        String value = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFKC)
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replace("..", " ").replaceAll("\\s+", " ").trim();
        // 空标题、超长标题和设备名都要回退/截断，避免模型输出影响 Windows 文件创建。
        if (value.isBlank()) value = fallback;
        if (value.length() > 60) value = value.substring(0, 60).trim();
        if (RESERVED.contains(value.toUpperCase(java.util.Locale.ROOT))) value = fallback;
        return value;
    }
}
