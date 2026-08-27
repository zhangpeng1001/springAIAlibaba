package com.example.agent.file;

import com.example.agent.model.AgentState;
import com.example.agent.model.Answer;
import com.example.agent.model.PlanItem;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** 将结构化 Answer 和任务元信息渲染为 Markdown，而非让 LLM 直接写入文件系统。 */
@Component
public class MarkdownWriter {
    /** 渲染单主题 Markdown。 */
    public String answer(Answer answer) {
        StringBuilder content = new StringBuilder("# ").append(answer.title()).append("\n\n")
                .append(answer.summary()).append("\n");
        for (Answer.Section section : answer.sections()) {
            content.append("\n## ").append(section.title()).append("\n\n").append(section.content()).append("\n");
        }
        return content.toString();
    }

    /** 渲染顶层说明，内容只来自已经锁定的任务状态。 */
    public String readme(AgentState state) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                .withZone(ZoneId.systemDefault());
        StringBuilder content = new StringBuilder("# ").append(state.getTitle()).append("\n\n")
                .append("## 原始问题\n\n").append(state.getQuestion()).append("\n\n")
                .append("## 学习目标\n\n").append(state.getCurrentPlan().goal()).append("\n\n")
                .append("## 最终确认纲要\n\n");
        for (PlanItem item : state.getCurrentPlan().items()) {
            content.append(item.order()).append(". ").append(item.title()).append("：")
                    .append(item.description()).append("\n");
        }
        return content.append("\n## 推荐阅读顺序\n\n按文件名前缀顺序阅读。\n\n")
                .append("生成时间：").append(formatter.format(state.getUpdatedAt())).append("\n").toString();
    }
}
