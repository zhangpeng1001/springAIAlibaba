package com.example.agent.llm;

import com.example.agent.model.Answer;
import com.example.agent.model.Plan;
import com.example.agent.model.PlanItem;
import com.example.agent.model.TaskAnalysis;

/**
 * 线性流程所需的最小 LLM 能力抽象。
 *
 * <p>模型只负责理解、规划、逐项写作和命名；流程状态、并发、文件路径与失败处理全部由 Java 控制，
 * 因而删除研究审核、答案审核和人工 Plan 对话后，接口也不会残留隐藏回环。</p>
 */
public interface LlmService {
    /** 理解用户问题并返回受限任务分类。 */
    TaskAnalysis analyze(String question);

    /** 根据用户问题生成唯一的初始纲要，版本固定为 1。 */
    Plan draftPlan(String question);

    /**
     * 直接针对一个纲要项生成完整详细解答，不再先生成独立研究中间对象。
     * @param question 用户原始问题
     * @param plan 完整初始纲要，用于说明整体目标和上下文
     * @param item 当前正在处理的纲要项
     * @return topicId 必须等于 item.id() 的结构化答案
     */
    Answer generateAnswer(String question, Plan plan, PlanItem item);

    /** 根据问题和初始纲要生成候选标题；调用方继续进行文件名安全过滤。 */
    String generateTitle(String question, Plan plan);
}
