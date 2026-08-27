package com.example.agent.file;

import com.example.agent.config.AgentProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** 管理 answer 根目录，并保证解析后的目录仍位于该根目录内。 */
@Component
public class OutputDirectoryManager {
    private final Path answerRoot;
    private final FileNameSanitizer sanitizer;

    public OutputDirectoryManager(AgentProperties properties, FileNameSanitizer sanitizer) throws IOException {
        this.answerRoot = Path.of(properties.getStorage().getAnswerRoot()).toAbsolutePath().normalize();
        this.sanitizer = sanitizer;
        Files.createDirectories(answerRoot);
    }

    /** 创建不覆盖旧结果的唯一输出目录。 */
    public Path create(String title, String taskId) {
        String safe = sanitizer.sanitize(title, "知识学习方案");
        Path candidate = verify(answerRoot.resolve(safe));
        if (Files.exists(candidate)) candidate = verify(answerRoot.resolve(safe + "-" + taskId.substring(Math.max(0, taskId.length() - 6))));
        try { return Files.createDirectories(candidate); }
        catch (IOException ex) { throw new IllegalStateException("创建输出目录失败", ex); }
    }

    /** 验证相对路径没有逃出 answer 根目录。 */
    public Path verify(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(answerRoot)) throw new IllegalArgumentException("非法输出路径");
        return normalized;
    }
}
