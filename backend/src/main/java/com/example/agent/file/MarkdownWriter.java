package com.example.agent.file;

import com.example.agent.model.AgentState;
import com.example.agent.model.Answer;
import com.example.agent.model.PlanItem;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 将结构化 Answer 和任务元信息渲染为 Markdown，而非让 LLM 直接写入文件系统。
 * 此类不处理路径、目录或文件 IO，确保“内容生成”和“文件权限”职责严格分离。
 */
@Component
public class MarkdownWriter {
    /**
     * 渲染单主题 Markdown。
     * 标题、摘要和 sections 均来自已经通过 Answer Review 的结构化对象，避免直接拼接模型原始全文。
     */
    public String answer(Answer answer) {
        StringBuilder content = new StringBuilder("# ").append(answer.title()).append("\n\n")
                .append(answer.summary()).append("\n");
        for (Answer.Section section : answer.sections()) {
            content.append("\n## ").append(section.title()).append("\n\n").append(section.content()).append("\n");
        }
        return content.toString();
    }

    /**
     * 渲染顶层 README。
     *
     * <p>只读取已锁定的 Plan 和状态中真实写入的字段，因此 README 既是用户阅读入口，也是任务
     * 输出范围和生成时间的可审计摘要。</p>
     *
     * @param state 已进入文件生成阶段的任务状态
     * @return 完整 README Markdown 文本
     */
    public String readme(AgentState state) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                .withZone(ZoneId.systemDefault());
        StringBuilder content = new StringBuilder("# ").append(state.getTitle()).append("\n\n")
                .append("## 原始问题\n\n").append(state.getQuestion()).append("\n\n")
                .append("## 学习目标\n\n").append(state.getCurrentPlan().goal()).append("\n\n")
                .append("## 最终确认纲要\n\n");
        // 这里保留 Plan 的原始顺序；FileGenerate 也按 order 写文件，二者保持一致。
        for (PlanItem item : state.getCurrentPlan().items()) {
            content.append(item.order()).append(". ").append(item.title()).append("：")
                    .append(item.description()).append("\n");
        }
        return content.append("\n## 推荐阅读顺序\n\n按文件名前缀顺序阅读。\n\n")
                .append("生成时间：").append(formatter.format(state.getUpdatedAt())).append("\n").toString();
    }
}
