package com.example.agent.llm;

import com.example.agent.model.Answer;
import com.example.agent.model.Plan;
import com.example.agent.model.PlanItem;
import com.example.agent.model.ResearchResult;
import com.example.agent.model.ReviewResult;
import com.example.agent.model.TaskAnalysis;
import java.util.List;

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
    ResearchResult research(String question, Plan plan, PlanItem item);

    /**
     * 根据上一版研究结果及审核意见定向修复单个主题。
     *
     * <p>默认实现保留为一次普通研究，确保已有的第三方/测试实现升级接口后仍可运行；
     * 真实模型适配器应覆写此方法，将 {@code previous} 和 {@code issues} 传入 Prompt。否则
     * Repair 节点会在不知道失败原因的情况下盲目重生成，容易反复触发同一个审核失败。</p>
     *
     * @param question 原始用户问题
     * @param plan 用户已锁定的纲要
     * @param item 本次只允许修复的主题
     * @param previous 上一版未通过审核的研究结果
     * @param issues 审核器给出的定向修复问题，按持久化顺序传递
     * @return 修复后的研究结果，调用方仍会校验主题 ID
     */
    default ResearchResult repairResearch(String question, Plan plan, PlanItem item, ResearchResult previous,
                                          List<ReviewResult.Issue> issues) {
        return research(question, plan, item);
    }

    /**
     * 审核单个研究结果，返回可被 Java 路由的结构化 PASS/FAIL 结果。
     */
    ReviewResult reviewResearch(Plan plan, PlanItem item, ResearchResult result);

    /**
     * 依据已审核的研究结果生成单个主题答案。
     */
    Answer generateAnswer(Plan plan, PlanItem item, ResearchResult research);

    /**
     * 根据上一版答案及审核意见定向修复单个主题答案。
     *
     * <p>与 {@link #repairResearch(String, Plan, PlanItem, ResearchResult, List)} 一样，默认回退到
     * 普通生成以兼容已有实现；生产适配器必须覆写，使审核意见真正影响下一轮输出。</p>
     *
     * @param plan 用户已锁定的纲要
     * @param item 本次只允许修复的主题
     * @param research 已通过研究审核的依据
     * @param previous 上一版未通过审核的答案
     * @param issues 审核器给出的定向修复问题
     * @return 修复后的答案，调用方仍会校验主题 ID
     */
    default Answer repairAnswer(Plan plan, PlanItem item, ResearchResult research, Answer previous,
                                List<ReviewResult.Issue> issues) {
        return generateAnswer(plan, item, research);
    }

    /**
     * 审核单个主题答案，失败时问题必须可用于定向修复。
     */
    ReviewResult reviewAnswer(PlanItem item, Answer answer);

    /**
     * 生成候选文档标题；调用方必须再做 Java 文件名净化和路径边界校验。
     */
    String generateTitle(String question, Plan plan);
}
