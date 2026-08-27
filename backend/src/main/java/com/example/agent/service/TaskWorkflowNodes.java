package com.example.agent.service;

import com.example.agent.config.AgentProperties;
import com.example.agent.exception.TaskException;
import com.example.agent.file.FileNameSanitizer;
import com.example.agent.file.MarkdownWriter;
import com.example.agent.file.OutputDirectoryManager;
import com.example.agent.llm.LlmService;
import com.example.agent.model.AgentState;
import com.example.agent.model.Answer;
import com.example.agent.model.Plan;
import com.example.agent.model.PlanFeedback;
import com.example.agent.model.PlanItem;
import com.example.agent.model.PlanVersion;
import com.example.agent.model.ResearchResult;
import com.example.agent.model.ReviewResult;
import com.example.agent.model.TaskStatus;
import com.example.agent.model.TaskAnalysis;
import com.example.agent.persistence.TaskStateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;

/**
 * StateGraph 的业务节点实现。
 *
 * <p>节点只处理一个明确阶段；每个阶段都先更新状态、再发事件、最后落业务工件，
 * 从而保证服务中断后恢复程序能够依据 state.json 判断哪些主题已经完成。</p>
 */
@Service
public class TaskWorkflowNodes {
    private final TaskStateStore stateStore;
    private final TaskEventService events;
    private final LlmService llm;
    private final AgentProperties properties;
    private final ExecutorService executor;
    private final OutputDirectoryManager outputDirectories;
    private final FileNameSanitizer fileNameSanitizer;
    private final MarkdownWriter markdownWriter;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public TaskWorkflowNodes(TaskStateStore stateStore, TaskEventService events, LlmService llm,
                             AgentProperties properties, ExecutorService agentExecutor,
                             OutputDirectoryManager outputDirectories, FileNameSanitizer fileNameSanitizer,
                             MarkdownWriter markdownWriter) {
        this.stateStore = stateStore;
        this.events = events;
        this.llm = llm;
        this.properties = properties;
        this.executor = agentExecutor;
        this.outputDirectories = outputDirectories;
        this.fileNameSanitizer = fileNameSanitizer;
        this.markdownWriter = markdownWriter;
    }

    /** 分析原始问题并产出受限枚举任务类型。 */
    public void taskAnalyze(String taskId) {
        ensureNotCancelled(taskId);
        stateStore.update(taskId, s -> {
            s.setCurrentNode("TASK_ANALYZE");
            s.setStatus(TaskStatus.ANALYZING);
        });
        AgentState before = stateStore.load(taskId);
        TaskAnalysis analysis = llm.analyze(before.getQuestion());
        require(analysis != null && java.util.Set.of("LEARNING", "INTERVIEW_PREPARATION", "TECH_RESEARCH", "KNOWLEDGE_SUMMARY").contains(analysis.taskType()),
                "LLM_INVALID_OUTPUT", "任务分析类型无效");
        AgentState state = stateStore.update(taskId, s -> s.setTaskType(analysis.taskType()));
        events.publish(taskId, "TASK_ANALYZED", "TASK_ANALYZE", Map.of("taskType", state.getTaskType()));
    }

    /** 生成 V1 纲要并立即持久化版本，随后必须等待人工确认。 */
    public void planDraft(String taskId) {
        ensureNotCancelled(taskId);
        AgentState before = stateStore.update(taskId, s -> { s.setCurrentNode("PLAN_DRAFT"); s.setStatus(TaskStatus.PLAN_DRAFTING); });
        Plan plan = llm.draftPlan(before.getQuestion());
        validatePlan(plan, 1);
        AgentState state = stateStore.update(taskId, s -> {
            s.setCurrentPlan(plan);
            s.setPlanVersion(plan.version());
            s.getPlanVersions().add(new PlanVersion(plan, "生成初始纲要", java.time.Instant.now(), null, false));
            s.setCurrentNode("WAITING_USER_PLAN");
            s.setStatus(TaskStatus.WAITING_USER_PLAN);
        });
        writeArtifact(taskId, "plans", "plan-v" + plan.version() + ".json", plan);
        events.publish(taskId, "PLAN_GENERATED", "PLAN_DRAFT", Map.of("planVersion", plan.version(), "plan", plan));
        events.publish(taskId, "PLAN_WAITING_USER", "WAITING_USER_PLAN", Map.of("planVersion", state.getPlanVersion(), "message", "请确认当前纲要"));
    }

    /** 基于用户自然语言意见生成新纲要；该节点绝不锁定或确认 Plan。 */
    public void planRevise(String taskId) {
        ensureNotCancelled(taskId);
        AgentState before = stateStore.update(taskId, s -> {
            require((s.getStatus() == TaskStatus.WAITING_USER_PLAN || s.getStatus() == TaskStatus.PLAN_REVISING) && !s.isPlanLocked(), "PLAN_REVISION_FAILED", "当前任务不允许修改纲要");
            require(s.getPendingPlanFeedback() != null && !s.getPendingPlanFeedback().isBlank(), "PLAN_REVISION_FAILED", "缺少待处理的修改意见");
            s.setCurrentNode("PLAN_REVISE");
            s.setStatus(TaskStatus.PLAN_REVISING);
        });
        String feedback = before.getPendingPlanFeedback();
        Plan plan = llm.revisePlan(before.getQuestion(), before.getCurrentPlan(), feedback);
        validatePlan(plan, before.getPlanVersion() + 1);
        String summary = "已根据你的意见更新纲要；当前为 V" + plan.version() + "。";
        AgentState state = stateStore.update(taskId, s -> {
            s.setCurrentPlan(plan);
            s.setPlanVersion(plan.version());
            s.getPlanFeedbackHistory().add(new PlanFeedback(before.getPlanVersion(), feedback, summary, java.time.Instant.now()));
            s.setPendingPlanFeedback(null);
            s.getPlanVersions().add(new PlanVersion(plan, summary, java.time.Instant.now(), null, false));
            s.setCurrentNode("WAITING_USER_PLAN");
            s.setStatus(TaskStatus.WAITING_USER_PLAN);
        });
        writeArtifact(taskId, "plans", "plan-v" + plan.version() + ".json", plan);
        events.publish(taskId, "PLAN_REVISED", "PLAN_REVISE", Map.of("planVersion", plan.version(), "summary", summary, "plan", plan));
        events.publish(taskId, "PLAN_WAITING_USER", "WAITING_USER_PLAN", Map.of("planVersion", state.getPlanVersion(), "message", "当前纲要已更新，请继续确认。"));
    }

    /** 只在确认接口验证版本之后锁定 Plan；一旦锁定，后续节点只能读取。 */
    public void planLock(String taskId) {
        ensureNotCancelled(taskId);
        // 自动阶段恢复会从 Graph 的 PLAN_LOCK 入口重新进入；已经锁定时必须幂等跳过，
        // 不能要求状态重新回到 WAITING_USER_PLAN，也不能重复修改确认时间。
        if (stateStore.load(taskId).isPlanLocked()) return;
        AgentState state = stateStore.update(taskId, s -> {
            require(s.getStatus() == TaskStatus.WAITING_USER_PLAN && s.isPlanConfirmed(), "PLAN_CONFIRM_FAILED", "纲要尚未被明确确认");
            s.setCurrentNode("PLAN_LOCK");
            s.setPlanLocked(true);
            s.setStatus(TaskStatus.PLAN_LOCKED);
            int index = s.getPlanVersions().size() - 1;
            if (index >= 0) {
                PlanVersion current = s.getPlanVersions().get(index);
                s.getPlanVersions().set(index, new PlanVersion(current.plan(), current.changeSummary(), current.createdAt(), java.time.Instant.now(), true));
            }
        });
        events.publish(taskId, "PLAN_LOCKED", "PLAN_LOCK", Map.of("planVersion", state.getPlanVersion()));
    }

    /** 并行生成尚未完成的研究结果；已持久化主题不会在恢复时重复调用模型。 */
    public void research(String taskId) {
        AgentState state = stateStore.update(taskId, s -> { s.setCurrentNode("RESEARCH"); s.setStatus(TaskStatus.RESEARCHING); });
        events.publish(taskId, "RESEARCH_STARTED", "RESEARCH", Map.of("total", state.getCurrentPlan().items().size()));
        parallel(state.getCurrentPlan().items().stream().filter(item -> !state.getResearchResults().containsKey(item.id())).toList(), item -> {
            ensureNotCancelled(taskId);
            AgentState snapshot = stateStore.load(taskId);
            ResearchResult result = llm.research(snapshot.getQuestion(), snapshot.getCurrentPlan(), item);
            require(result != null && item.id().equals(result.topicId()), "LLM_INVALID_OUTPUT", "研究结果主题标识不匹配");
            stateStore.update(taskId, s -> s.getResearchResults().put(item.id(), result));
            writeArtifact(taskId, "research", item.id() + ".json", result);
            events.publish(taskId, "RESEARCH_PROGRESS", "RESEARCH", Map.of("topicId", item.id(), "topic", item.title()));
        });
    }

    /** 审核所有研究主题，返回是否可路由到 Answer；失败则由图进入修复节点。 */
    public boolean researchReview(String taskId) {
        AgentState start = stateStore.update(taskId, s -> { s.setCurrentNode("RESEARCH_REVIEW"); s.setStatus(TaskStatus.RESEARCH_REVIEWING); s.setResearchReviewRound(s.getResearchReviewRound() + 1); });
        events.publish(taskId, "RESEARCH_REVIEWING", "RESEARCH_REVIEW", Map.of("round", start.getResearchReviewRound()));
        List<PlanItem> failed = new ArrayList<>();
        for (PlanItem item : start.getCurrentPlan().items()) {
            ResearchResult result = stateStore.load(taskId).getResearchResults().get(item.id());
            ReviewResult review = llm.reviewResearch(start.getCurrentPlan(), item, result);
            stateStore.update(taskId, s -> s.getReviewResults().put("research:" + item.id(), review));
            writeArtifact(taskId, "reviews", "research-" + item.id() + ".json", review);
            if (!review.passed()) failed.add(item);
        }
        if (failed.isEmpty()) return true;
        if (start.getResearchReviewRound() >= properties.getLimits().getMaxResearchReviewRounds()) {
            throw new TaskException("RESEARCH_REVIEW_MAX_ROUNDS", "研究审核达到最大修复次数");
        }
        events.publish(taskId, "RESEARCH_REVIEW_FAILED", "RESEARCH_REVIEW", Map.of("round", start.getResearchReviewRound(), "failedTopicIds", failed.stream().map(PlanItem::id).toList()));
        return false;
    }

    /** 仅重做审核失败的研究主题，而不是重新执行所有主题。 */
    public void researchRepair(String taskId) {
        AgentState state = stateStore.update(taskId, s -> { s.setCurrentNode("RESEARCH_REPAIR"); s.setStatus(TaskStatus.RESEARCH_REPAIRING); });
        List<PlanItem> failed = state.getCurrentPlan().items().stream().filter(item -> {
            ReviewResult review = state.getReviewResults().get("research:" + item.id());
            return review != null && !review.passed();
        }).toList();
        events.publish(taskId, "RESEARCH_REPAIRED", "RESEARCH_REPAIR", Map.of("count", failed.size()));
        parallel(failed, item -> {
            AgentState snapshot = stateStore.load(taskId);
            ResearchResult result = llm.research(snapshot.getQuestion(), snapshot.getCurrentPlan(), item);
            stateStore.update(taskId, s -> s.getResearchResults().put(item.id(), result));
            writeArtifact(taskId, "research", item.id() + ".json", result);
        });
    }

    /** 并行生成通过研究审核后尚未生成的答案。 */
    public void answerGenerate(String taskId) {
        AgentState state = stateStore.update(taskId, s -> { s.setCurrentNode("ANSWER_GENERATE"); s.setStatus(TaskStatus.ANSWER_GENERATING); });
        events.publish(taskId, "ANSWER_STARTED", "ANSWER_GENERATE", Map.of("total", state.getCurrentPlan().items().size()));
        parallel(state.getCurrentPlan().items().stream().filter(item -> !state.getAnswers().containsKey(item.id())).toList(), item -> {
            AgentState snapshot = stateStore.load(taskId);
            Answer answer = llm.generateAnswer(snapshot.getCurrentPlan(), item, snapshot.getResearchResults().get(item.id()));
            require(answer != null && item.id().equals(answer.topicId()), "LLM_INVALID_OUTPUT", "答案主题标识不匹配");
            stateStore.update(taskId, s -> s.getAnswers().put(item.id(), answer));
            writeArtifact(taskId, "answers", item.id() + ".json", answer);
            events.publish(taskId, "ANSWER_PROGRESS", "ANSWER_GENERATE", Map.of("topicId", item.id(), "topic", item.title()));
        });
    }

    /** 审核每份 Answer；全部通过才允许生成文件。 */
    public boolean answerReview(String taskId) {
        AgentState start = stateStore.update(taskId, s -> { s.setCurrentNode("ANSWER_REVIEW"); s.setStatus(TaskStatus.ANSWER_REVIEWING); s.setAnswerReviewRound(s.getAnswerReviewRound() + 1); });
        events.publish(taskId, "ANSWER_REVIEWING", "ANSWER_REVIEW", Map.of("round", start.getAnswerReviewRound()));
        List<PlanItem> failed = new ArrayList<>();
        for (PlanItem item : start.getCurrentPlan().items()) {
            Answer answer = stateStore.load(taskId).getAnswers().get(item.id());
            ReviewResult review = llm.reviewAnswer(item, answer);
            stateStore.update(taskId, s -> s.getReviewResults().put("answer:" + item.id(), review));
            writeArtifact(taskId, "reviews", "answer-" + item.id() + ".json", review);
            if (!review.passed()) failed.add(item);
        }
        if (failed.isEmpty()) return true;
        if (start.getAnswerReviewRound() >= properties.getLimits().getMaxAnswerReviewRounds()) {
            throw new TaskException("ANSWER_REVIEW_MAX_ROUNDS", "内容审核达到最大修复次数");
        }
        events.publish(taskId, "ANSWER_REVIEW_FAILED", "ANSWER_REVIEW", Map.of("round", start.getAnswerReviewRound(), "failedTopicIds", failed.stream().map(PlanItem::id).toList()));
        return false;
    }

    /** 仅重新生成审核失败的答案。 */
    public void answerRepair(String taskId) {
        AgentState state = stateStore.update(taskId, s -> { s.setCurrentNode("ANSWER_REPAIR"); s.setStatus(TaskStatus.ANSWER_REPAIRING); });
        List<PlanItem> failed = state.getCurrentPlan().items().stream().filter(item -> {
            ReviewResult review = state.getReviewResults().get("answer:" + item.id());
            return review != null && !review.passed();
        }).toList();
        events.publish(taskId, "ANSWER_REPAIRED", "ANSWER_REPAIR", Map.of("count", failed.size()));
        parallel(failed, item -> {
            AgentState snapshot = stateStore.load(taskId);
            Answer answer = llm.generateAnswer(snapshot.getCurrentPlan(), item, snapshot.getResearchResults().get(item.id()));
            stateStore.update(taskId, s -> s.getAnswers().put(item.id(), answer));
            writeArtifact(taskId, "answers", item.id() + ".json", answer);
        });
    }

    /** 调用标题 Agent 后立即执行 Java 侧文件名净化。 */
    public void titleGenerate(String taskId) {
        AgentState before = stateStore.update(taskId, s -> { s.setCurrentNode("TITLE_GENERATE"); s.setStatus(TaskStatus.TITLE_GENERATING); });
        String title = fileNameSanitizer.sanitize(llm.generateTitle(before.getQuestion(), before.getCurrentPlan()), "知识学习方案");
        stateStore.update(taskId, s -> s.setTitle(title));
    }

    /** 创建受控目录并写入 README、主题 Markdown 与 metadata.json。 */
    public void fileGenerate(String taskId) {
        AgentState state = stateStore.update(taskId, s -> { s.setCurrentNode("FILE_GENERATE"); s.setStatus(TaskStatus.FILE_GENERATING); });
        events.publish(taskId, "FILE_GENERATING", "FILE_GENERATE", Map.of());
        Path directory = state.getOutputDirectory() == null ? outputDirectories.create(state.getTitle(), taskId) : Path.of(state.getOutputDirectory());
        // 目录刚创建就记入 state.json；这样 FILE_GENERATE 中断后会复用同一目录，
        // 避免恢复任务因标题冲突额外创建第二份输出。
        if (state.getOutputDirectory() == null) {
            stateStore.update(taskId, s -> s.setOutputDirectory(directory.toString()));
            state = stateStore.load(taskId);
        }
        List<String> names = new ArrayList<>();
        try {
            writeText(directory.resolve("README.md"), markdownWriter.readme(state));
            names.add("README.md");
            for (PlanItem item : state.getCurrentPlan().items().stream().sorted(Comparator.comparingInt(PlanItem::order)).toList()) {
                Answer answer = state.getAnswers().get(item.id());
                String name = String.format("%02d-%s.md", item.order(), fileNameSanitizer.sanitize(item.title(), item.id()));
                writeText(outputDirectories.verify(directory.resolve(name)), markdownWriter.answer(answer));
                names.add(name);
                events.publish(taskId, "FILE_WRITTEN", "FILE_GENERATE", Map.of("file", name));
            }
            stateStore.update(taskId, s -> s.setOutputFiles(names));
            writeText(directory.resolve("metadata.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(stateStore.load(taskId)));
            names.add("metadata.json");
            stateStore.update(taskId, s -> s.setOutputFiles(names));
        } catch (IOException ex) { throw new TaskException("FILE_WRITE_FAILED", "写入 Markdown 文件失败", true); }
    }

    /** 收集真实已写文件并置为成功终态。 */
    public void resultCollect(String taskId) {
        AgentState state = stateStore.update(taskId, s -> { s.setCurrentNode("RESULT_COLLECT"); s.setStatus(TaskStatus.SUCCESS); });
        events.publish(taskId, "TASK_SUCCESS", "RESULT_COLLECT", Map.of("outputDirectory", state.getOutputDirectory(), "files", state.getOutputFiles()));
    }

    private void parallel(List<PlanItem> items, java.util.function.Consumer<PlanItem> work) {
        try {
            CompletableFuture.allOf(items.stream().map(item -> CompletableFuture.runAsync(() -> work.accept(item), executor)).toArray(CompletableFuture[]::new)).join();
        } catch (java.util.concurrent.CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw ex;
        }
    }

    private void ensureNotCancelled(String taskId) {
        AgentState state = stateStore.load(taskId);
        if (state.isCancelRequested()) throw new TaskException("WORKFLOW_CANCELLED", "任务已被取消");
    }

    private void validatePlan(Plan plan, int expectedVersion) {
        require(plan != null && plan.version() == expectedVersion, "LLM_INVALID_OUTPUT", "纲要版本无效");
        require(plan.items() != null && !plan.items().isEmpty() && plan.items().size() <= properties.getLimits().getMaxPlanItems(), "LLM_INVALID_OUTPUT", "纲要主题数量无效");
        require(plan.items().stream().allMatch(item -> item.id() != null && !item.id().isBlank() && item.title() != null && !item.title().isBlank()), "LLM_INVALID_OUTPUT", "纲要存在缺少标识或标题的主题");
    }

    private void writeArtifact(String taskId, String dir, String file, Object value) {
        try {
            Path path = stateStore.file(taskId, dir, file);
            Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        } catch (IOException ex) { throw new TaskException("FILE_WRITE_FAILED", "写入任务工件失败", true); }
    }

    private void writeText(Path path, String content) throws IOException { Files.writeString(path, content); }

    private void require(boolean condition, String code, String message) { if (!condition) throw new TaskException(code, message); }
}
