package com.example.agent.file;

import com.example.agent.config.AgentProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * 管理 answer 根目录，并保证解析后的目录仍位于该根目录内。
 * 这是 LLM 输出与真实文件系统之间的最终安全边界。
 */
@Component
public class OutputDirectoryManager {
    /** 规范化后的最终输出根目录，所有 Markdown 和 metadata 必须位于其下。 */
    private final Path answerRoot;
    /** 将候选标题变为安全目录名片段的组件。 */
    private final FileNameSanitizer sanitizer;

    /**
     * 初始化并确保 answer 根目录可写。
     *
     * @throws IOException 根目录无法创建时阻止应用启动，避免任务最后阶段才失败
     */
    public OutputDirectoryManager(AgentProperties properties, FileNameSanitizer sanitizer) throws IOException {
        this.answerRoot = Path.of(properties.getStorage().getAnswerRoot()).toAbsolutePath().normalize();
        this.sanitizer = sanitizer;
        Files.createDirectories(answerRoot);
    }

    /**
     * 创建不覆盖历史结果的输出目录。
     *
     * <p>同名标题已有目录时追加 taskId 短后缀，而不是覆盖旧任务文档；恢复中的任务会优先使用
     * 已落盘 outputDirectory，因此不会重复进入此分支。</p>
     *
     * @param title 已由标题 Agent 给出的候选标题
     * @param taskId 服务端生成的任务标识，用于重名消歧
     * @return 已创建且位于 answerRoot 内的目录
     */
    public Path create(String title, String taskId) {
        String safe = sanitizer.sanitize(title, "知识学习方案");
        Path candidate = verify(answerRoot.resolve(safe));
        if (Files.exists(candidate)) candidate = verify(answerRoot.resolve(safe + "-" + taskId.substring(Math.max(0, taskId.length() - 6))));
        try { return Files.createDirectories(candidate); }
        catch (IOException ex) { throw new IllegalStateException("创建输出目录失败", ex); }
    }

    /**
     * 验证解析后的路径没有借助 ../、绝对路径或 Windows 驱动器路径逃出 answer 根目录。
     *
     * @param candidate 待验证路径
     * @return 可安全写入的规范化绝对路径
     * @throws IllegalArgumentException 候选路径不属于 answer 根目录时抛出
     */
    public Path verify(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(answerRoot)) throw new IllegalArgumentException("非法输出路径");
        return normalized;
    }
}
