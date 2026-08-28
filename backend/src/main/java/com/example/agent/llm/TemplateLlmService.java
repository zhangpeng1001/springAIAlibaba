package com.example.agent.llm;

import com.example.agent.model.Answer;
import com.example.agent.model.Plan;
import com.example.agent.model.PlanItem;
import com.example.agent.model.TaskAnalysis;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 默认离线 LLM 实现。
 *
 * <p>它使用确定性模板验证完整线性流程，不代表真实模型的知识质量；启用 openai profile 后由远程适配器替换。</p>
 */
@Service
@Profile("!openai")
public class TemplateLlmService implements LlmService {
    /** 根据关键词生成稳定的任务类型，便于本地无密钥运行和自动化测试。 */
    @Override
    public TaskAnalysis analyze(String question) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        String type = text.contains("面试") ? "INTERVIEW_PREPARATION" : text.contains("学习") ? "LEARNING"
                : text.contains("研究") ? "TECH_RESEARCH" : "KNOWLEDGE_SUMMARY";
        return new TaskAnalysis(type, "GENERAL", "围绕用户问题生成可执行的知识纲要");
    }

    /** 生成固定三项初始纲要，保证离线环境能覆盖目录和多文件输出。 */
    @Override
    public Plan draftPlan(String question) {
        String normalized = question == null ? "知识学习" : question.trim();
        String title = normalized.replaceAll("[？?。！!]", "");
        if (title.length() > 10) title = title.substring(0, 10);
        List<PlanItem> items = List.of(
                new PlanItem("TOPIC-001", "核心概念", "建立主题的基本概念与术语体系", 1, true, "NORMAL"),
                new PlanItem("TOPIC-002", "关键知识点", "围绕目标拆解必须掌握的知识点", 2, true, "DEEP"),
                new PlanItem("TOPIC-003", "实践与误区", "给出实践建议、常见错误和进阶方向", 3, true, "NORMAL"));
        return new Plan(1, title.isBlank() ? "知识学习方案" : title,
                "围绕“" + normalized + "”建立可执行的知识体系", items);
    }

    /** 针对单个纲要项直接生成结构化详细答案，模拟一次模型调用。 */
    @Override
    public Answer generateAnswer(String question, Plan plan, PlanItem item) {
        String context = question == null || question.isBlank() ? plan.goal() : question.trim();
        return new Answer(item.id(), item.title(), "本章围绕“" + context + "”回答“" + item.title() + "”。",
                List.of(
                        new Answer.Section("核心说明", "本节解释 **" + item.title() + "** 的关键概念、适用边界和必要背景。"),
                        new Answer.Section("实践建议", "建议结合实际项目验证“" + item.title() + "”，记录有效做法与常见误区。")));
    }

    /** 返回 Plan 标题作为候选值，最终目录名仍由 Java 安全组件净化。 */
    @Override
    public String generateTitle(String question, Plan plan) {
        return plan.title() == null || plan.title().isBlank() ? "知识学习方案" : plan.title();
    }
}
