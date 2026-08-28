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
import com.example.agent.model.PlanItem;
import com.example.agent.model.TaskAnalysis;
import com.example.agent.model.TaskStatus;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * 线性 StateGraph 节点实现。
 *
 * <p>每个节点只负责一个固定阶段，并在开始和完成时持久化状态。答案生成允许纲要项受限并行，
 * 但状态写入仍由 TaskStateStore 串行化；这样既降低总耗时，也保持断点恢复和文件列表的准确性。</p>
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

    /** 注入线性节点所需的状态、模型、并发和文件组件。 */
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

    /** 理解问题并保存固定枚举的任务类型，避免自由文本影响后续状态路由。 */
    public void taskAnalyze(String taskId) {
        ensureNotCancelled(taskId);
        AgentState before = stateStore.update(taskId, s -> {
            if (s.getTaskType() != null && !s.getTaskType().isBlank()) return;
            s.setCurrentNode("TASK_ANALYZE");
            s.setStatus(TaskStatus.ANALYZING);
        });
        if (before.getTaskType() != null && !before.getTaskType().isBlank()) return;
        TaskAnalysis analysis = llm.analyze(before.getQuestion());
        require(analysis != null && Set.of("LEARNING", "INTERVIEW_PREPARATION", "TECH_RESEARCH", "KNOWLEDGE_SUMMARY").contains(analysis.taskType()),
                "LLM_INVALID_OUTPUT", "任务分析类型无效");
        AgentState state = stateStore.update(taskId, s -> s.setTaskType(analysis.taskType()));
        events.publish(taskId, "TASK_ANALYZED", "TASK_ANALYZE", Map.of("taskType", state.getTaskType()));
    }

    /** 生成唯一初始纲要并立即进入答案阶段，不再等待人工确认。 */
    public void planDraft(String taskId) {
        ensureNotCancelled(taskId);
        AgentState before = stateStore.update(taskId, s -> {
            s.setCurrentNode("PLAN_DRAFT");
            s.setStatus(TaskStatus.PLAN_DRAFTING);
        });
        if (before.getCurrentPlan() != null) return;
        Plan plan = llm.draftPlan(before.getQuestion());
        validatePlan(plan);
        AgentState state = stateStore.update(taskId, s -> {
            s.setCurrentPlan(plan);
            s.setCurrentNode("PLAN_DRAFT");
            s.setStatus(TaskStatus.PLAN_DRAFTING);
        });
        writeArtifact(taskId, "plans", "plan-v1.json", plan);
        events.publish(taskId, "PLAN_GENERATED", "PLAN_DRAFT", Map.of("plan", plan));
    }

    /**
     * 按纲要项受限并行生成答案；已落盘的答案在恢复时跳过，避免重复消耗模型调用。
     */
    public void answerGenerate(String taskId) {
        ensureNotCancelled(taskId);
        AgentState state = stateStore.update(taskId, s -> {
            s.setCurrentNode("ANSWER_GENERATE");
            s.setStatus(TaskStatus.ANSWER_GENERATING);
        });
        require(state.getCurrentPlan() != null, "WORKFLOW_STATE_INVALID", "生成答案前缺少初始纲要");
        List<PlanItem> pending = state.getCurrentPlan().items().stream()
                .filter(item -> !state.getAnswers().containsKey(item.id())).toList();
        events.publish(taskId, "ANSWER_STARTED", "ANSWER_GENERATE", Map.of("total", state.getCurrentPlan().items().size(), "pending", pending.size()));
        parallel(pending, item -> {
            ensureNotCancelled(taskId);
            AgentState snapshot = stateStore.load(taskId);
            Answer answer = llm.generateAnswer(snapshot.getQuestion(), snapshot.getCurrentPlan(), item);
            require(answer != null && item.id().equals(answer.topicId()), "LLM_INVALID_OUTPUT", "答案主题标识不匹配：" + item.id());
            require(answer.title() != null && !answer.title().isBlank() && answer.summary() != null && !answer.summary().isBlank()
                            && answer.sections() != null && !answer.sections().isEmpty()
                            && answer.sections().stream().allMatch(section -> section != null && section.title() != null
                            && !section.title().isBlank() && section.content() != null && !section.content().isBlank()),
                    "LLM_INVALID_OUTPUT", "答案内容为空：" + item.id());
            stateStore.update(taskId, s -> s.getAnswers().put(item.id(), answer));
            writeArtifact(taskId, "answers", item.id() + ".json", answer);
            events.publish(taskId, "ANSWER_PROGRESS", "ANSWER_GENERATE", Map.of("topicId", item.id(), "topic", item.title()));
        });
    }

    /** 生成并净化标题；已有标题表示恢复时该节点已完成。 */
    public void titleGenerate(String taskId) {
        ensureNotCancelled(taskId);
        AgentState before = stateStore.update(taskId, s -> {
            s.setCurrentNode("TITLE_GENERATE");
            s.setStatus(TaskStatus.TITLE_GENERATING);
        });
        if (before.getTitle() != null && !before.getTitle().isBlank()) return;
        require(before.getCurrentPlan() != null, "WORKFLOW_STATE_INVALID", "生成标题前缺少初始纲要");
        String title = fileNameSanitizer.sanitize(llm.generateTitle(before.getQuestion(), before.getCurrentPlan()), "知识学习方案");
        AgentState state = stateStore.update(taskId, s -> s.setTitle(title));
        events.publish(taskId, "TITLE_GENERATED", "TITLE_GENERATE", Map.of("title", state.getTitle()));
    }

    /** 创建受控 answer/{标题}/ 目录并写入 README、主题 Markdown 和 metadata。 */
    public void fileGenerate(String taskId) {
        ensureNotCancelled(taskId);
        AgentState state = stateStore.update(taskId, s -> {
            s.setCurrentNode("FILE_GENERATE");
            s.setStatus(TaskStatus.FILE_GENERATING);
        });
        require(state.getTitle() != null && state.getCurrentPlan() != null, "WORKFLOW_STATE_INVALID", "文件生成前缺少标题或纲要");
        events.publish(taskId, "FILE_GENERATING", "FILE_GENERATE", Map.of());
        Path directory = state.getOutputDirectory() == null ? outputDirectories.create(state.getTitle(), taskId)
                : outputDirectories.verify(Path.of(state.getOutputDirectory()));
        if (state.getOutputDirectory() == null) {
            stateStore.update(taskId, s -> s.setOutputDirectory(directory.toString()));
            state = stateStore.load(taskId);
        }
        List<String> names = new ArrayList<>();
        try {
            // 输出目录可能在进程退出后被外部清理；恢复时重新创建同一路径，避免标题再次生成导致目录漂移。
            Files.createDirectories(directory);
            // 文件阶段可能在写入中途进程退出；已存在的文件直接复用，避免恢复时重复覆盖用户已经拿到的内容。
            if (!Files.exists(directory.resolve("README.md"))) writeText(directory.resolve("README.md"), markdownWriter.readme(state));
            names.add("README.md");
            for (PlanItem item : state.getCurrentPlan().items().stream().sorted(Comparator.comparingInt(PlanItem::order)).toList()) {
                Answer answer = state.getAnswers().get(item.id());
                require(answer != null, "WORKFLOW_STATE_INVALID", "缺少纲要项答案：" + item.id());
                String name = String.format("%02d-%s.md", item.order(), fileNameSanitizer.sanitize(item.title(), item.id()));
                Path target = outputDirectories.verify(directory.resolve(name));
                boolean written = !Files.exists(target);
                if (written) writeText(target, markdownWriter.answer(answer));
                names.add(name);
                if (written) events.publish(taskId, "FILE_WRITTEN", "FILE_GENERATE", Map.of("file", name));
            }
            // 先把完整文件清单（包含 metadata）写入状态，再序列化 metadata，确保交付清单与状态快照一致。
            names.add("metadata.json");
            stateStore.update(taskId, s -> s.setOutputFiles(names));
            if (!Files.exists(directory.resolve("metadata.json"))) {
                writeText(directory.resolve("metadata.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(stateStore.load(taskId)));
            }
        } catch (IOException ex) {
            throw new TaskException("FILE_WRITE_FAILED", "写入 Markdown 文件失败", true);
        }
    }

    /** 所有文件写入成功后设置 SUCCESS，并把目录和文件清单发布给 SSE 客户端。 */
    public void resultCollect(String taskId) {
        ensureNotCancelled(taskId);
        AgentState state = stateStore.update(taskId, s -> {
            require(!s.isCancelRequested(), "WORKFLOW_CANCELLED", "任务已被取消");
            s.setCurrentNode("RESULT_COLLECT");
            s.setStatus(TaskStatus.SUCCESS);
        });
        events.publish(taskId, "TASK_SUCCESS", "RESULT_COLLECT", Map.of("outputDirectory", state.getOutputDirectory(), "outputFiles", state.getOutputFiles()));
    }

    /** 并行执行独立纲要项，并把子任务异常还原为业务 RuntimeException。 */
    private void parallel(List<PlanItem> items, Consumer<PlanItem> work) {
        // 工作流父任务本身也占用 agentExecutor；并行度为 1 时若继续提交子任务再 join 会自我死锁，
        // 因此显式降级为当前线程串行执行，既保留配置语义，也保证低资源部署可以完成任务。
        if (properties.getExecutor().getParallelism() <= 1) {
            items.forEach(work);
            return;
        }
        try {
            CompletableFuture.allOf(items.stream().map(item -> CompletableFuture.runAsync(() -> work.accept(item), executor))
                    .toArray(CompletableFuture[]::new)).join();
        } catch (java.util.concurrent.CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw ex;
        }
    }

    /** 检查协作式取消标记，避免取消后的晚到结果进入下一个阶段。 */
    private void ensureNotCancelled(String taskId) {
        if (stateStore.load(taskId).isCancelRequested()) throw new TaskException("WORKFLOW_CANCELLED", "任务已被取消");
    }

    /** 校验初始纲要的版本、主题数量和稳定 ID，防止非法结构进入并行阶段。 */
    private void validatePlan(Plan plan) {
        require(plan != null && plan.version() == 1, "LLM_INVALID_OUTPUT", "初始纲要版本无效");
        require(plan.items() != null && !plan.items().isEmpty() && plan.items().size() <= properties.getLimits().getMaxPlanItems(),
                "LLM_INVALID_OUTPUT", "初始纲要主题数量无效");
        require(plan.items().stream().allMatch(item -> item != null && item.id() != null && !item.id().isBlank()
                        && item.title() != null && !item.title().isBlank()), "LLM_INVALID_OUTPUT", "纲要存在缺少标识或标题的主题");
    }

    /** 将结构化中间结果写入固定任务工件目录，便于恢复和排查。 */
    private void writeArtifact(String taskId, String dir, String file, Object value) {
        try {
            Files.createDirectories(stateStore.taskDir(taskId).resolve(dir));
            Files.writeString(stateStore.file(taskId, dir, file), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        } catch (IOException ex) {
            throw new TaskException("FILE_WRITE_FAILED", "写入任务工件失败", true);
        }
    }

    /** 写入已经通过路径校验的最终 Markdown 文本。 */
    private void writeText(Path path, String content) throws IOException { Files.writeString(path, content); }

    /** 把节点前置条件失败转换成稳定业务错误码。 */
    private void require(boolean condition, String code, String message) { if (!condition) throw new TaskException(code, message); }
}
