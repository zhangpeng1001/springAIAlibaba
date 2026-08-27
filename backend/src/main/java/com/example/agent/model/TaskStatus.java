package com.example.agent.model;

/**
 * 任务生命周期状态。
 * 状态由 Java 工作流控制，不允许由 LLM 直接修改，避免模型输出影响程序路由。
 */
public enum TaskStatus {
    /** 初始状态文件已创建，尚未提交图执行。 */
    CREATED,
    /** Task Analyzer 正在理解用户问题。 */
    ANALYZING,
    /** Planner 正在生成首版纲要。 */
    PLAN_DRAFTING,
    /** 强制 Human Gate：等待用户发送修改意见或点击显式确认。 */
    WAITING_USER_PLAN,
    /** 已收到用户意见，Plan Conversation 正在生成下一版本。 */
    PLAN_REVISING,
    /** 用户确认的 Plan 已被锁定，后续阶段不可修改其范围。 */
    PLAN_LOCKED,
    /** 按 PlanItem 并行研究知识主题。 */
    RESEARCHING,
    /** 自动审核全部研究结果。 */
    RESEARCH_REVIEWING,
    /** 仅修复研究审核失败的主题。 */
    RESEARCH_REPAIRING,
    /** 基于已审核研究结果生成主题答案。 */
    ANSWER_GENERATING,
    /** 自动审核全部主题答案。 */
    ANSWER_REVIEWING,
    /** 仅重新生成答案审核失败的主题。 */
    ANSWER_REPAIRING,
    /** 生成最终文档标题候选并进行安全净化。 */
    TITLE_GENERATING,
    /** 写入 README、各主题 Markdown 和 metadata.json。 */
    FILE_GENERATING,
    /** 所有文件已经成功写入的正常终态。 */
    SUCCESS,
    /** 不可自动继续的异常终态，errorCode/errorMessage 说明原因。 */
    FAILED,
    /** 用户请求取消后的终态，协作式节点不再推进后续工作。 */
    CANCELLED
}
