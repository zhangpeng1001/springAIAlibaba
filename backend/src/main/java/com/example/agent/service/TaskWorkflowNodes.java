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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;

/**
 * StateGraph 的业务节点实现。
 *
 * <p>节点只处理一个明确阶段；每个阶段都先更新状态、再发事件、最后落业务工件，
 * 从而保证服务中断后恢复程序能够依据 state.json 判断哪些主题已经完成。</p>
 *
 * <p>重要边界：本类从不相信 LLM 可以控制状态机。模型只返回 DTO；主题 ID、审核轮次、
 * Plan 锁定、文件路径和下一节点均由 Java 代码验证并决定。</p>
 */
@Service
public class TaskWorkflowNodes {
    /**
     * 基于 JSON 文件的任务状态仓库；内部通过任务锁串行化状态变更，防止并行主题互相覆盖。
     */
    private final TaskStateStore stateStore;
    /**
     * 状态变化后的事件发布器。事件会先持久化到 events.jsonl，再尽力实时推送给浏览器。
     */
    private final TaskEventService events;
    /**
     * 语言能力边界。此类只调用接口，不依赖 OpenAI、DashScope 等具体厂商。
     */
    private final LlmService llm;
    /**
     * 运行上限，例如 Plan 最大主题数、研究审核最大轮数；所有循环保护都从此配置读取。
     */
    private final AgentProperties properties;
    /**
     * 用于同一任务不同主题的并行 LLM 调用。状态文件写入仍由 stateStore 锁串行化。
     */
    private final ExecutorService executor;
    /**
     * 受控 answer 根目录管理器，负责确保输出路径不会逃逸到项目之外。
     */
    private final OutputDirectoryManager outputDirectories;
    /**
     * LLM 标题和 PlanItem 标题到文件名之间的安全过滤器。
     */
    private final FileNameSanitizer fileNameSanitizer;
    /**
     * 将结构化答案渲染为 Markdown 的纯 Java 组件，LLM 永远不直接操作文件系统。
     */
    private final MarkdownWriter markdownWriter;
    /**
     * 专门序列化中间工件和 metadata.json 的 JSON 映射器，已注册时间类型支持。
     */
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * 注入节点运行所需的领域服务。
     *
     * @param stateStore 任务状态及中间工件目录入口
     * @param events SSE / JSONL 事件服务
     * @param llm 厂商中立的 LLM 适配器
     * @param properties 系统运行限制
     * @param agentExecutor 有界并行执行器
     * @param outputDirectories 最终输出目录安全管理器
     * @param fileNameSanitizer 文件名净化器
     * @param markdownWriter Markdown 渲染器
     */
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

    /**
     * 执行 TASK_ANALYZE 节点。
     *
     * <p>先把节点状态落为 ANALYZING，再调用模型。即使模型进程崩溃，恢复程序也能从
     * currentNode 识别中断点；模型返回后必须校验 taskType 是否属于允许枚举。</p>
     *
     * @param taskId 当前任务标识
     */
    public void taskAnalyze(String taskId) {
        ensureNotCancelled(taskId);
        stateStore.update(taskId, s -> {
            s.setCurrentNode("TASK_ANALYZE");
            s.setStatus(TaskStatus.ANALYZING);
        });
        AgentState before = stateStore.load(taskId);
        TaskAnalysis analysis = llm.analyze(before.getQuestion());
        // TaskAnalysis 的目标和说明只用于审计；真正控制流程的 taskType 必须为固定枚举。
        require(analysis != null && java.util.Set.of("LEARNING", "INTERVIEW_PREPARATION", "TECH_RESEARCH", "KNOWLEDGE_SUMMARY").contains(analysis.taskType()),
                "LLM_INVALID_OUTPUT", "任务分析类型无效");
        AgentState state = stateStore.update(taskId, s -> s.setTaskType(analysis.taskType()));
        events.publish(taskId, "TASK_ANALYZED", "TASK_ANALYZE", Map.of("taskType", state.getTaskType()));
    }

    /**
     * 执行 PLAN_DRAFT 节点，创建不可变的 Plan V1。
     *
     * <p>无论模型认为方案多完整，最后都必须转入 WAITING_USER_PLAN；这是防止系统跳过
     * Human Gate 的最高优先级规则。</p>
     *
     * @param taskId 当前任务标识
     */
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
        // Plan 版本同时嵌入总状态和独立文件，前者用于恢复，后者用于审计与历史对比。
        writeArtifact(taskId, "plans", "plan-v" + plan.version() + ".json", plan);
        events.publish(taskId, "PLAN_GENERATED", "PLAN_DRAFT", Map.of("planVersion", plan.version(), "plan", plan));
        events.publish(taskId, "PLAN_WAITING_USER", "WAITING_USER_PLAN", Map.of("planVersion", state.getPlanVersion(), "message", "请确认当前纲要"));
    }

    /**
     * 执行 PLAN_REVISE 节点，将用户意见转换为新的 Plan 版本。
     *
     * <p>pendingPlanFeedback 先于异步执行落盘，因此服务重启可以重放本次修订；完成后清空
     * 该字段，防止下一次恢复重复应用相同意见。该节点只生成新 Plan，绝不确认或锁定 Plan。</p>
     *
     * @param taskId 当前任务标识
     */
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

    /**
     * 执行 PLAN_LOCK 节点。
     *
     * <p>该节点只接受 confirmPlan 已经校验过的状态。锁定后研究阶段不得擅自向 Plan 添加主题；
     * 如果研究发现范围缺口，只能以 Review 问题记录，不能改变用户已确认的工作范围。</p>
     *
     * @param taskId 当前任务标识
     */
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

    /**
     * 执行 RESEARCH 节点并并行生成尚未完成的主题研究。
     *
     * <p>并行只作用于模型调用；每个主题返回后单独写 state.json 和 research/{topicId}.json。
     * 已存在结果的主题会跳过，从而使服务恢复具备幂等性。</p>
     *
     * @param taskId 当前任务标识
     */
    public void research(String taskId) {
        AgentState state = stateStore.update(taskId, s -> { s.setCurrentNode("RESEARCH"); s.setStatus(TaskStatus.RESEARCHING); });
        events.publish(taskId, "RESEARCH_STARTED", "RESEARCH", Map.of("total", state.getCurrentPlan().items().size()));
        parallel(state.getCurrentPlan().items().stream().filter(item -> !state.getResearchResults().containsKey(item.id())).toList(), item -> {
            ensureNotCancelled(taskId);
            AgentState snapshot = stateStore.load(taskId);
            ResearchResult result = llm.research(snapshot.getQuestion(), snapshot.getCurrentPlan(), item);
            validateResearchResult(result, item);
            // topicId 校验通过后才可写入，避免模型把 A 主题结果覆盖到 B 主题键下。
            stateStore.update(taskId, s -> s.getResearchResults().put(item.id(), result));
            writeArtifact(taskId, "research", item.id() + ".json", result);
            events.publish(taskId, "RESEARCH_PROGRESS", "RESEARCH", Map.of("topicId", item.id(), "topic", item.title()));
        });
    }

    /**
     * 执行 RESEARCH_REVIEW 节点。
     *
     * @param taskId 当前任务标识
     * @return 所有主题均通过时返回 true，Graph 据此进入 ANSWER_GENERATE；否则进入 RESEARCH_REPAIR
     * @throws TaskException 达到配置的最大研究审核轮数时抛出，避免无限回边
     */
    public boolean researchReview(String taskId) {
        AgentState start = stateStore.update(taskId, s -> { s.setCurrentNode("RESEARCH_REVIEW"); s.setStatus(TaskStatus.RESEARCH_REVIEWING); s.setResearchReviewRound(s.getResearchReviewRound() + 1); });
        events.publish(taskId, "RESEARCH_REVIEWING", "RESEARCH_REVIEW", Map.of("round", start.getResearchReviewRound()));
        List<PlanItem> failed = new ArrayList<>();
        for (PlanItem item : start.getCurrentPlan().items()) {
            ResearchResult result = stateStore.load(taskId).getResearchResults().get(item.id());
            ReviewResult review = normalizeReviewResult(llm.reviewResearch(start.getCurrentPlan(), item, result), item);
            stateStore.update(taskId, s -> s.getReviewResults().put("research:" + item.id(), review));
            writeArtifact(taskId, "reviews", "research-" + item.id() + ".json", review);
            if (!review.passed()) failed.add(item);
        }
        // 只有全部主题通过才允许进入写作，避免把不完整研究“带病”传给 Answer Agent。
        if (failed.isEmpty()) return true;
        if (start.getResearchReviewRound() >= properties.getLimits().getMaxResearchReviewRounds()) {
            throw new TaskException("RESEARCH_REVIEW_MAX_ROUNDS", "研究审核达到最大修复次数");
        }
        events.publish(taskId, "RESEARCH_REVIEW_FAILED", "RESEARCH_REVIEW", Map.of("round", start.getResearchReviewRound(), "failedTopicIds", failed.stream().map(PlanItem::id).toList()));
        return false;
    }

    /**
     * 执行 RESEARCH_REPAIR 节点，只重新研究审核失败的主题。
     *
     * @param taskId 当前任务标识
     */
    public void researchRepair(String taskId) {
        AgentState state = stateStore.update(taskId, s -> { s.setCurrentNode("RESEARCH_REPAIR"); s.setStatus(TaskStatus.RESEARCH_REPAIRING); });
        List<PlanItem> failed = state.getCurrentPlan().items().stream().filter(item -> {
            ReviewResult review = state.getReviewResults().get("research:" + item.id());
            return review != null && !review.passed();
        }).toList();
        events.publish(taskId, "RESEARCH_REPAIRED", "RESEARCH_REPAIR", Map.of("count", failed.size()));
        parallel(failed, item -> {
            AgentState snapshot = stateStore.load(taskId);
            ReviewResult previousReview = snapshot.getReviewResults().get("research:" + item.id());
            ResearchResult previous = snapshot.getResearchResults().get(item.id());
            ResearchResult result = llm.repairResearch(snapshot.getQuestion(), snapshot.getCurrentPlan(), item, previous,
                    previousReview == null ? List.of() : previousReview.issues());
            // Repair 是覆写已有工件的路径，必须和首次研究执行相同的主题 ID 校验，
            // 否则兼容模型的异常输出可能在此处把其他主题的结果覆盖进当前 Map 键。
            validateResearchResult(result, item);
            stateStore.update(taskId, s -> s.getResearchResults().put(item.id(), result));
            writeArtifact(taskId, "research", item.id() + ".json", result);
        });
    }

    /**
     * 执行 ANSWER_GENERATE 节点，基于已通过研究审核的内容并行生成答案。
     *
     * @param taskId 当前任务标识
     */
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

    /**
     * 执行 ANSWER_REVIEW 节点。
     *
     * @param taskId 当前任务标识
     * @return 全部答案通过时返回 true，Graph 才可以进入标题和文件阶段
     * @throws TaskException 达到最大内容审核轮数时抛出
     */
    public boolean answerReview(String taskId) {
        AgentState start = stateStore.update(taskId, s -> { s.setCurrentNode("ANSWER_REVIEW"); s.setStatus(TaskStatus.ANSWER_REVIEWING); s.setAnswerReviewRound(s.getAnswerReviewRound() + 1); });
        events.publish(taskId, "ANSWER_REVIEWING", "ANSWER_REVIEW", Map.of("round", start.getAnswerReviewRound()));
        List<PlanItem> failed = new ArrayList<>();
        for (PlanItem item : start.getCurrentPlan().items()) {
            Answer answer = stateStore.load(taskId).getAnswers().get(item.id());
            ReviewResult review = normalizeReviewResult(llm.reviewAnswer(item, answer), item);
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

    /**
     * 执行 ANSWER_REPAIR 节点，只重新生成未通过审核的答案。
     *
     * @param taskId 当前任务标识
     */
    public void answerRepair(String taskId) {
        AgentState state = stateStore.update(taskId, s -> { s.setCurrentNode("ANSWER_REPAIR"); s.setStatus(TaskStatus.ANSWER_REPAIRING); });
        List<PlanItem> failed = state.getCurrentPlan().items().stream().filter(item -> {
            ReviewResult review = state.getReviewResults().get("answer:" + item.id());
            return review != null && !review.passed();
        }).toList();
        events.publish(taskId, "ANSWER_REPAIRED", "ANSWER_REPAIR", Map.of("count", failed.size()));
        parallel(failed, item -> {
            AgentState snapshot = stateStore.load(taskId);
            ReviewResult previousReview = snapshot.getReviewResults().get("answer:" + item.id());
            Answer previous = snapshot.getAnswers().get(item.id());
            Answer answer = llm.repairAnswer(snapshot.getCurrentPlan(), item, snapshot.getResearchResults().get(item.id()), previous,
                    previousReview == null ? List.of() : previousReview.issues());
            // 与首次写作保持同一边界：修复结果必须仍属于当前主题，不能跨主题污染输出文件。
            require(answer != null && item.id().equals(answer.topicId()), "LLM_INVALID_OUTPUT", "修复后的答案主题标识不匹配");
            stateStore.update(taskId, s -> s.getAnswers().put(item.id(), answer));
            writeArtifact(taskId, "answers", item.id() + ".json", answer);
        });
    }

    /**
     * 执行 TITLE_GENERATE 节点。
     * LLM 生成的标题只是候选值，必须经 Java 净化后才保存，不能将模型输出直接用于文件路径。
     */
    public void titleGenerate(String taskId) {
        AgentState before = stateStore.update(taskId, s -> { s.setCurrentNode("TITLE_GENERATE"); s.setStatus(TaskStatus.TITLE_GENERATING); });
        String title = fileNameSanitizer.sanitize(llm.generateTitle(before.getQuestion(), before.getCurrentPlan()), "知识学习方案");
        stateStore.update(taskId, s -> s.setTitle(title));
    }

    /**
     * 执行 FILE_GENERATE 节点，创建受控目录并写入 README、主题 Markdown 与 metadata.json。
     *
     * <p>输出目录一经创建立即回写状态，确保恢复时写入同一个目录；随后按 Plan 的 order 排序，
     * 使 README 中的阅读顺序与磁盘文件名顺序一致。</p>
     *
     * @param taskId 当前任务标识
     */
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
            // 先保存已有 Markdown 列表，再写 metadata；即使 metadata 写入失败也可从状态恢复重试。
            stateStore.update(taskId, s -> s.setOutputFiles(names));
            writeText(directory.resolve("metadata.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(stateStore.load(taskId)));
            names.add("metadata.json");
            stateStore.update(taskId, s -> s.setOutputFiles(names));
        } catch (IOException ex) { throw new TaskException("FILE_WRITE_FAILED", "写入 Markdown 文件失败", true); }
    }

    /**
     * 执行 RESULT_COLLECT 节点：只在所有文件成功写入后设置 SUCCESS 并发送终态事件。
     */
    public void resultCollect(String taskId) {
        AgentState state = stateStore.update(taskId, s -> { s.setCurrentNode("RESULT_COLLECT"); s.setStatus(TaskStatus.SUCCESS); });
        events.publish(taskId, "TASK_SUCCESS", "RESULT_COLLECT", Map.of("outputDirectory", state.getOutputDirectory(), "files", state.getOutputFiles()));
    }

    /**
     * 并行处理独立主题并等待所有任务完成。
     *
     * <p>CompletableFuture#allOf 会把任一子任务异常包装为 CompletionException；这里解包 RuntimeException，
     * 以保留 LLM_INVALID_OUTPUT、FILE_WRITE_FAILED 等业务错误码给上层统一失败处理。</p>
     *
     * @param items 可并行执行的主题列表
     * @param work 单个主题的业务动作
     */
    private void parallel(List<PlanItem> items, java.util.function.Consumer<PlanItem> work) {
        try {
            CompletableFuture.allOf(items.stream().map(item -> CompletableFuture.runAsync(() -> work.accept(item), executor)).toArray(CompletableFuture[]::new)).join();
        } catch (java.util.concurrent.CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw ex;
        }
    }

    /**
     * 在耗时节点开始前检查协作式取消标记。
     * 不直接终止线程，避免打断正在写入状态文件或等待远程模型响应的临界过程。
     */
    private void ensureNotCancelled(String taskId) {
        AgentState state = stateStore.load(taskId);
        if (state.isCancelRequested()) throw new TaskException("WORKFLOW_CANCELLED", "任务已被取消");
    }

    /**
     * 校验 LLM 生成的 Plan 能作为内部状态使用。
     *
     * <p>不要把自然语言或缺少 ID 的主题直接保存为核心状态：后续 Research、Answer、Review
     * 依赖稳定 topicId 做 Map 键与文件名。</p>
     */
    private void validatePlan(Plan plan, int expectedVersion) {
        require(plan != null && plan.version() == expectedVersion, "LLM_INVALID_OUTPUT", "纲要版本无效");
        require(plan.items() != null && !plan.items().isEmpty() && plan.items().size() <= properties.getLimits().getMaxPlanItems(), "LLM_INVALID_OUTPUT", "纲要主题数量无效");
        require(plan.items().stream().allMatch(item -> item.id() != null && !item.id().isBlank() && item.title() != null && !item.title().isBlank()), "LLM_INVALID_OUTPUT", "纲要存在缺少标识或标题的主题");
    }

    /**
     * 校验研究结果能够安全进入审核与后续写作阶段。
     *
     * <p>仅校验 topicId 无法阻止空 details 或空问题列表进入状态文件；这类结果会让审核模型
     * 反复给出笼统的失败意见，最终耗尽修复轮次。因此在首次研究和定向修复两个入口统一执行
     * 最小结构校验，并在模型输出不合格时直接以稳定错误码终止。</p>
     *
     * @param result 模型返回的研究结果
     * @param item 当前锁定主题
     */
    private void validateResearchResult(ResearchResult result, PlanItem item) {
        require(result != null && item.id().equals(result.topicId()), "LLM_INVALID_OUTPUT", "研究结果主题标识不匹配");
        require(!result.details().isEmpty(), "LLM_INVALID_OUTPUT", "研究结果缺少可审核的细节");
        require(result.details().stream().allMatch(detail -> detail.id() != null && !detail.id().isBlank()
                        && detail.title() != null && !detail.title().isBlank() && !detail.questions().isEmpty()
                        && detail.questions().stream().allMatch(question -> question != null && !question.isBlank())),
                "LLM_INVALID_OUTPUT", "研究结果存在缺少标识、标题或问题的细节");
    }

    /**
     * 将审核器的原始判断规整为工作流可执行的通过语义。
     *
     * <p>模型常把“还可以进一步完善”的 MEDIUM/LOW 建议标为 {@code passed=false}。若直接把这类
     * 建议送入回边，模型即使已满足 Plan 也会在每轮被要求继续扩写，最终触发最大修复次数。这里
     * 只让 HIGH/CRITICAL/BLOCKER 级、且审核器明确要求失败的问题进入 Repair；其余建议仍会持久化，
     * 但不会阻塞后续写作。空审核结果仍视为模型协议错误，不能悄悄放行。</p>
     *
     * @param review 模型返回的原始审核结果
     * @param item 当前被审核的锁定主题
     * @return 与工作流路由一致的审核结果
     */
    private ReviewResult normalizeReviewResult(ReviewResult review, PlanItem item) {
        require(review != null, "LLM_INVALID_OUTPUT", "审核结果为空：" + item.id());
        if (review.passed()) return review;
        require(!review.issues().isEmpty(), "LLM_INVALID_OUTPUT", "审核未通过但缺少可修复问题：" + item.id());
        boolean hasBlockingIssue = review.issues().stream().anyMatch(this::isBlockingIssue);
        if (hasBlockingIssue) return review;
        // 保留审核意见和评分，以便前端展示改进建议；只把路由含义改为“可继续”，避免状态与图分支不一致。
        return new ReviewResult(true, review.score(), review.issues());
    }

    /**
     * 判断一个审核问题是否足以阻断工作流。
     *
     * <p>兼容中英文大小写与常见的 BLOCKER 标记，避免不同 OpenAI-compatible 模型的大小写差异
     * 改变工作流分支。MEDIUM/LOW/INFO 只作为质量建议，不应消耗有限的自动修复轮次。</p>
     */
    private boolean isBlockingIssue(ReviewResult.Issue issue) {
        if (issue == null || issue.severity() == null) return false;
        String severity = issue.severity().trim().toUpperCase(Locale.ROOT);
        return "HIGH".equals(severity) || "CRITICAL".equals(severity) || "BLOCKER".equals(severity)
                || "高".equals(severity) || "严重".equals(severity) || "阻断".equals(severity);
    }

    /**
     * 写入任务中间工件，供审计和恢复时检查。
     * 工件目录由 stateStore 生成，不接受 LLM 提供的目录或文件名。
     */
    private void writeArtifact(String taskId, String dir, String file, Object value) {
        try {
            Path path = stateStore.file(taskId, dir, file);
            Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        } catch (IOException ex) { throw new TaskException("FILE_WRITE_FAILED", "写入任务工件失败", true); }
    }

    /** 写入已经过 OutputDirectoryManager 校验的最终文本文件。 */
    private void writeText(Path path, String content) throws IOException { Files.writeString(path, content); }

    /**
     * 将内部前置条件失败转换为带稳定错误码的任务异常。
     * 调用方会把该异常写入 AgentState 并通过 SSE 通知前端。
     */
    private void require(boolean condition, String code, String message) { if (!condition) throw new TaskException(code, message); }
}
