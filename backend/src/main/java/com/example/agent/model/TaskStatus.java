package com.example.agent.model;

/**
 * 线性任务生命周期。
 * 状态不再包含人工确认、研究审核或修复回环，模型输出也不能直接修改状态。
 */
public enum TaskStatus {
    /** 初始状态文件已创建，尚未提交图执行。 */
    CREATED,
    /** Task Analyzer 正在理解用户问题。 */
    ANALYZING,
    /** 正在生成唯一的初始纲要。 */
    PLAN_DRAFTING,
    /** 正在按纲要项并行生成详细解答。 */
    ANSWER_GENERATING,
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
