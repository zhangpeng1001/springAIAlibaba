package com.example.agent.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务的可持久化状态快照。
 *
 * <p>状态只保留线性流程真正需要的数据：任务理解结果、初始纲要、逐项答案以及最终文件信息。
 * 所有修改仍由 TaskStateStore 在任务锁内完成，保证并行答案生成不会互相覆盖。</p>
 */
public class AgentState {
    /** 任务目录名和 API 路径参数，创建后不可变。 */
    private String taskId;
    /** 用户提交的原始问题；所有 Agent Prompt 均以此作为问题上下文。 */
    private String question;
    /** Task Analyzer 输出的受限任务类型，用于展示和后续策略扩展。 */
    private String taskType;
    /** 当前线性生命周期状态，由 Java 工作流控制。 */
    private TaskStatus status;
    /** 最近一个已开始或已完成的工作流节点，便于恢复、调试与前端进度展示。 */
    private String currentNode;
    /** 自动生成的初始纲要；流程中不再提供人工修改或确认。 */
    private Plan currentPlan;
    /** 按稳定纲要项 ID 保存详细解答，是 Markdown Writer 的唯一输入。 */
    private Map<String, Answer> answers = new HashMap<>();
    /** 经过 Java 文件名安全过滤后的最终文档标题。 */
    private String title;
    /** 已创建的最终输出目录；在文件阶段创建后立即落盘，确保恢复时复用同一目录。 */
    private String outputDirectory;
    /** 已真实写入的文件名列表，前端仅展示本字段而不自行推导目录内容。 */
    private List<String> outputFiles = new ArrayList<>();
    /** 失败时的稳定业务错误码，例如 LLM_INVALID_OUTPUT。 */
    private String errorCode;
    /** 面向用户和排查人员的失败说明，不保存完整模型响应以免泄露 Prompt 内容。 */
    private String errorMessage;
    /** 协作式取消标记；耗时节点在安全边界检查后停止进入后续阶段。 */
    private boolean cancelRequested;
    /** 初次创建时间，用于任务列表排序和 metadata.json。 */
    private Instant createdAt;
    /** 每次经业务 setter 修改后自动刷新，用于恢复和前端轮询判断。 */
    private Instant updatedAt;

    /** Jackson 反序列化所需的无参构造器；业务创建请使用 {@link #created(String, String)}。 */
    public AgentState() { }

    /**
     * 创建最小合法初始状态。
     *
     * @param taskId 已生成的目录安全任务标识
     * @param question 已通过 API 校验的用户问题
     * @return 处于 CREATED / START 的任务快照，随后由 TaskStateStore 立即落盘
     */
    public static AgentState created(String taskId, String question) {
        AgentState state = new AgentState();
        state.taskId = taskId;
        state.question = question;
        state.status = TaskStatus.CREATED;
        state.currentNode = "START";
        state.createdAt = Instant.now();
        state.updatedAt = state.createdAt;
        return state;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; touch(); }
    public String getCurrentNode() { return currentNode; }
    public void setCurrentNode(String currentNode) { this.currentNode = currentNode; touch(); }
    public Plan getCurrentPlan() { return currentPlan; }
    public void setCurrentPlan(Plan currentPlan) { this.currentPlan = currentPlan; touch(); }
    public Map<String, Answer> getAnswers() { return answers; }
    public void setAnswers(Map<String, Answer> value) { this.answers = value == null ? new HashMap<>() : new HashMap<>(value); touch(); }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; touch(); }
    public String getOutputDirectory() { return outputDirectory; }
    public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; touch(); }
    public List<String> getOutputFiles() { return outputFiles; }
    public void setOutputFiles(List<String> value) { this.outputFiles = value == null ? new ArrayList<>() : new ArrayList<>(value); touch(); }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; touch(); }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; touch(); }
    public boolean isCancelRequested() { return cancelRequested; }
    public void setCancelRequested(boolean cancelRequested) { this.cancelRequested = cancelRequested; touch(); }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /**
     * 统一刷新更新时间。
     * createdAt 不在这里更新，以保留任务创建时刻；Jackson 恢复调用 setter 时也会得到新的修改时间，
     * 因此读取状态使用 ObjectMapper 的字段填充策略不会触发本方法。
     */
    private void touch() { this.updatedAt = Instant.now(); }
}
