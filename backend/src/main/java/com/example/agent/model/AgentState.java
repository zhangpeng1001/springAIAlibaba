package com.example.agent.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务的可持久化状态快照。
 *
 * <p>该类刻意使用普通 Java Bean，便于 Jackson 在服务重启后恢复；所有写操作
 * 都由 TaskStateStore 在任务锁内完成，避免并行主题覆盖彼此结果。</p>
 */
public class AgentState {
    private String taskId;
    private String question;
    private String taskType;
    private TaskStatus status;
    private String currentNode;
    private Plan currentPlan;
    private int planVersion;
    private boolean planConfirmed;
    private boolean planLocked;
    private List<PlanVersion> planVersions = new ArrayList<>();
    private List<PlanFeedback> planFeedbackHistory = new ArrayList<>();
    /** 正在异步处理的用户意见，用于服务重启后继续 Plan 修订。 */
    private String pendingPlanFeedback;
    private Map<String, ResearchResult> researchResults = new HashMap<>();
    private Map<String, Answer> answers = new HashMap<>();
    private Map<String, ReviewResult> reviewResults = new HashMap<>();
    private int researchReviewRound;
    private int answerReviewRound;
    private String title;
    private String outputDirectory;
    private List<String> outputFiles = new ArrayList<>();
    private String errorCode;
    private String errorMessage;
    private boolean cancelRequested;
    private Instant createdAt;
    private Instant updatedAt;

    public AgentState() { }

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
    public int getPlanVersion() { return planVersion; }
    public void setPlanVersion(int planVersion) { this.planVersion = planVersion; touch(); }
    public boolean isPlanConfirmed() { return planConfirmed; }
    public void setPlanConfirmed(boolean planConfirmed) { this.planConfirmed = planConfirmed; touch(); }
    public boolean isPlanLocked() { return planLocked; }
    public void setPlanLocked(boolean planLocked) { this.planLocked = planLocked; touch(); }
    public List<PlanVersion> getPlanVersions() { return planVersions; }
    public void setPlanVersions(List<PlanVersion> value) { this.planVersions = value == null ? new ArrayList<>() : new ArrayList<>(value); touch(); }
    public List<PlanFeedback> getPlanFeedbackHistory() { return planFeedbackHistory; }
    public void setPlanFeedbackHistory(List<PlanFeedback> value) { this.planFeedbackHistory = value == null ? new ArrayList<>() : new ArrayList<>(value); touch(); }
    public String getPendingPlanFeedback() { return pendingPlanFeedback; }
    public void setPendingPlanFeedback(String pendingPlanFeedback) { this.pendingPlanFeedback = pendingPlanFeedback; touch(); }
    public Map<String, ResearchResult> getResearchResults() { return researchResults; }
    public void setResearchResults(Map<String, ResearchResult> value) { this.researchResults = value == null ? new HashMap<>() : new HashMap<>(value); touch(); }
    public Map<String, Answer> getAnswers() { return answers; }
    public void setAnswers(Map<String, Answer> value) { this.answers = value == null ? new HashMap<>() : new HashMap<>(value); touch(); }
    public Map<String, ReviewResult> getReviewResults() { return reviewResults; }
    public void setReviewResults(Map<String, ReviewResult> value) { this.reviewResults = value == null ? new HashMap<>() : new HashMap<>(value); touch(); }
    public int getResearchReviewRound() { return researchReviewRound; }
    public void setResearchReviewRound(int value) { this.researchReviewRound = value; touch(); }
    public int getAnswerReviewRound() { return answerReviewRound; }
    public void setAnswerReviewRound(int value) { this.answerReviewRound = value; touch(); }
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

    private void touch() { this.updatedAt = Instant.now(); }
}
