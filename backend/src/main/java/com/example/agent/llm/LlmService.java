package com.example.agent.llm;

import com.example.agent.model.Answer;
import com.example.agent.model.Plan;
import com.example.agent.model.ResearchResult;
import com.example.agent.model.ReviewResult;
import com.example.agent.model.TaskAnalysis;

/**
 * LLM 能力抽象层。
 * 工作流只依赖此接口，不感知具体模型厂商；生产环境可替换为 Spring AI ChatModel 适配器，
 * 测试环境使用确定性的 Fake/Template 实现以保证测试不依赖网络。
 */
public interface LlmService {
    /**
     * 理解用户问题并输出受限任务分类。
     * @param question 原始用户问题
     * @return 不含流程控制指令的 TaskAnalysis，由 Java 代码继续校验枚举值
     */
    TaskAnalysis analyze(String question);

    /**
     * 创建首版可供人工确认的纲要。
     * @param question 原始问题
     * @return Plan V1；调用方负责校验版本、主题数量和稳定 ID
     */
    Plan draftPlan(String question);

    /**
     * 根据单轮用户意见生成新的纲要版本。
     * @param question 原始问题
     * @param current 当前尚未锁定的 Plan
     * @param feedback 用户自然语言修改意见
     * @return 新 Plan；该方法不具备确认/锁定权限
     */
    Plan revisePlan(String question, Plan current, String feedback);

    /**
     * 只针对一个锁定主题生成研究结果，不允许扩大 Plan 范围。
     */
    ResearchResult research(String question, Plan plan, com.example.agent.model.PlanItem item);

    /**
     * 审核单个研究结果，返回可被 Java 路由的结构化 PASS/FAIL 结果。
     */
    ReviewResult reviewResearch(Plan plan, com.example.agent.model.PlanItem item, ResearchResult result);

    /**
     * 依据已审核的研究结果生成单个主题答案。
     */
    Answer generateAnswer(Plan plan, com.example.agent.model.PlanItem item, ResearchResult research);

    /**
     * 审核单个主题答案，失败时问题必须可用于定向修复。
     */
    ReviewResult reviewAnswer(com.example.agent.model.PlanItem item, Answer answer);

    /**
     * 生成候选文档标题；调用方必须再做 Java 文件名净化和路径边界校验。
     */
    String generateTitle(String question, Plan plan);
}
