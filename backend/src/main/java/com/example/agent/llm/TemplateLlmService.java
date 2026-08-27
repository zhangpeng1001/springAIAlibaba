package com.example.agent.llm;

import com.example.agent.model.Answer;
import com.example.agent.model.Plan;
import com.example.agent.model.PlanItem;
import com.example.agent.model.ResearchResult;
import com.example.agent.model.ReviewResult;
import com.example.agent.model.TaskAnalysis;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

/**
 * 可运行的离线默认模型。
 * 该实现让系统在没有 API Key 时仍能完整演示 Workflow、人工确认和文件输出；
 * 生产接入时用 Spring AI ChatModel 实现替换此 Bean，不改变控制器和状态协议。
 */
@Service
@Profile("!openai")
public class TemplateLlmService implements LlmService {
    /** 根据关键词提供确定性的离线任务分类，供无模型密钥时验证结构化状态流。 */
    @Override
    public TaskAnalysis analyze(String question) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        String type = text.contains("面试") ? "INTERVIEW_PREPARATION" : text.contains("学习") ? "LEARNING"
                : text.contains("研究") ? "TECH_RESEARCH" : "KNOWLEDGE_SUMMARY";
        return new TaskAnalysis(type, "GENERAL", "围绕用户问题生成可确认的知识纲要");
    }

    @Override
    public Plan draftPlan(String question) {
        String normalized = question == null ? "知识学习" : question.trim();
        String title = normalized.replaceAll("[？?。！!]", "");
        if (title.length() > 10) title = title.substring(0, 10);
        List<PlanItem> items = new ArrayList<>();
        items.add(new PlanItem("TOPIC-001", "核心概念", "建立主题的基本概念与术语体系", 1, true, "NORMAL"));
        items.add(new PlanItem("TOPIC-002", "关键知识点", "围绕目标拆解必须掌握的知识点", 2, true, "DEEP"));
        items.add(new PlanItem("TOPIC-003", "实践与误区", "给出实践建议、常见错误和进阶方向", 3, true, "NORMAL"));
        return new Plan(1, title.isBlank() ? "知识学习方案" : title, "围绕“" + normalized + "”建立可执行的知识体系", items);
    }

    @Override
    public Plan revisePlan(String question, Plan current, String feedback) {
        List<PlanItem> items = new ArrayList<>(current.items());
        String lower = feedback == null ? "" : feedback.toLowerCase(Locale.ROOT);
        if (lower.contains("删除") || lower.contains("移除")) {
            items.removeIf(item -> feedback.contains(item.title()));
        }
        if (lower.contains("增加") || lower.contains("添加")) {
            String topic = feedback.replaceAll(".*?(增加|添加)", "").replaceAll("[，,。].*", "").trim();
            if (!topic.isBlank()) items.add(new PlanItem("TOPIC-" + String.format("%03d", items.size() + 1), topic,
                    "根据用户意见补充的知识主题", items.size() + 1, true, "NORMAL"));
        }
        for (int i = 0; i < items.size(); i++) {
            PlanItem old = items.get(i);
            items.set(i, new PlanItem(old.id(), old.title(), old.description(), i + 1, old.required(), old.depth()));
        }
        return new Plan(current.version() + 1, current.title(), current.goal(), items);
    }

    @Override
    public ResearchResult research(String question, Plan plan, PlanItem item) {
        return new ResearchResult(item.id(), item.title(), List.of(
                new ResearchResult.ResearchDetail(item.id() + "-01", "概念与边界",
                        List.of(item.title() + "是什么", "核心组成和适用边界是什么")),
                new ResearchResult.ResearchDetail(item.id() + "-02", "实践要点",
                        List.of("如何在项目中使用", "常见误区和排查方法"))));
    }

    @Override
    public ReviewResult reviewResearch(Plan plan, PlanItem item, ResearchResult result) {
        boolean pass = result != null && result.details() != null && !result.details().isEmpty();
        return new ReviewResult(pass, pass ? 0.92 : 0.2,
                pass ? List.of() : List.of(new ReviewResult.Issue("MISSING", "HIGH", "研究结果缺少有效细节")));
    }

    @Override
    public Answer generateAnswer(Plan plan, PlanItem item, ResearchResult research) {
        List<Answer.Section> sections = research.details().stream()
                .map(detail -> new Answer.Section(detail.title(), "本节围绕 **" + item.title() + "** 展开，覆盖："
                        + String.join("、", detail.questions()) + "。\n\n建议结合实际项目进行练习，并记录验证结果。"))
                .toList();
        return new Answer(item.id(), item.title(), "本章总结“" + item.title() + "”的核心内容和实践路径。", sections);
    }

    @Override
    public ReviewResult reviewAnswer(PlanItem item, Answer answer) {
        boolean pass = answer != null && answer.sections() != null && !answer.sections().isEmpty();
        return new ReviewResult(pass, pass ? 0.9 : 0.2,
                pass ? List.of() : List.of(new ReviewResult.Issue("MISSING", "HIGH", "答案缺少章节内容")));
    }

    @Override
    public String generateTitle(String question, Plan plan) {
        return plan.title() == null || plan.title().isBlank() ? "知识学习方案" : plan.title();
    }
}
