package com.example.agent.service;

import com.example.agent.config.AgentProperties;
import com.example.agent.file.FileNameSanitizer;
import com.example.agent.file.MarkdownWriter;
import com.example.agent.file.OutputDirectoryManager;
import com.example.agent.llm.LlmService;
import com.example.agent.model.AgentState;
import com.example.agent.model.Answer;
import com.example.agent.model.Plan;
import com.example.agent.model.PlanItem;
import com.example.agent.model.ResearchResult;
import com.example.agent.model.ReviewResult;
import com.example.agent.model.TaskAnalysis;
import com.example.agent.persistence.EventLogStore;
import com.example.agent.persistence.TaskStateStore;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证审核回边的定向修复与非阻塞建议路由。
 *
 * <p>这些测试不访问真实模型：使用可记录入参的替身确认上一版工件和审核问题确实传入 Repair。
 * 这能防止未来重构时重新出现“审核失败后仅以相同 Prompt 重试”的无限回环问题。</p>
 */
class TaskWorkflowNodesTest {
    /** JUnit 为每个测试提供独立目录，避免状态 JSON 和中间工件相互污染。 */
    @TempDir Path temp;

    /**
     * 审核失败后，研究修复必须接收被拒绝的上一版结果和可执行的审核意见。
     *
     * <p>只传当前 PlanItem 会让模型不了解已完成内容与具体缺口，修复等同于随机重生成；
     * 本测试直接覆盖导致日志中耗尽三轮审核次数的根因。</p>
     */
    @Test
    void researchRepairPassesPreviousResultAndReviewIssuesToLlm() throws Exception {
        try (NodeHarness harness = new NodeHarness(temp)) {
            PlanItem item = topic();
            ResearchResult previous = research(item, "旧问题");
            List<ReviewResult.Issue> issues = List.of(new ReviewResult.Issue("MISSING", "HIGH", "补齐事务边界"));
            harness.prepare(item, previous, new ReviewResult(false, 0.4, issues));
            ResearchResult repaired = research(item, "已修复的问题");
            harness.llm.repairedResearch = repaired;

            harness.nodes.researchRepair(NodeHarness.TASK_ID);

            assertEquals(previous, harness.llm.previousResearch);
            assertEquals(issues, harness.llm.researchRepairIssues);
            assertEquals(repaired, harness.store.load(NodeHarness.TASK_ID).getResearchResults().get(item.id()));
        }
    }

    /**
     * 答案审核同样使用 Repair 回边，必须复用相同的反馈传递约束。
     *
     * <p>研究阶段修复后若答案阶段仍盲目重生成，长答案会以同样方式耗尽 Answer Review 次数；
     * 因此对镜像路径增加回归保护，确保两类工件的行为一致。</p>
     */
    @Test
    void answerRepairPassesPreviousAnswerAndReviewIssuesToLlm() throws Exception {
        try (NodeHarness harness = new NodeHarness(temp)) {
            PlanItem item = topic();
            Answer previous = answer(item, "旧答案");
            List<ReviewResult.Issue> issues = List.of(new ReviewResult.Issue("MISSING", "HIGH", "补齐异常回滚说明"));
            harness.prepareAnswer(item, research(item, "研究依据"), previous, new ReviewResult(false, 0.4, issues));
            Answer repaired = answer(item, "已修复的答案");
            harness.llm.repairedAnswer = repaired;

            harness.nodes.answerRepair(NodeHarness.TASK_ID);

            assertEquals(previous, harness.llm.previousAnswer);
            assertEquals(issues, harness.llm.answerRepairIssues);
            assertEquals(repaired, harness.store.load(NodeHarness.TASK_ID).getAnswers().get(item.id()));
        }
    }

    /**
     * 中低优先级建议不能把已满足主题目标的研究结果送入有限次数的修复回边。
     *
     * <p>真实日志中模型将“可进一步深入”标为 false 且 severity=MEDIUM，导致三轮后任务失败；
     * 此处断言这类建议会保留在状态中，但规范化为可继续的 passed=true。</p>
     */
    @Test
    void advisoryResearchReviewDoesNotEnterRepairLoop() throws Exception {
        try (NodeHarness harness = new NodeHarness(temp)) {
            PlanItem item = topic();
            ResearchResult result = research(item, "完整问题");
            harness.prepare(item, result, null);
            harness.llm.researchReview = new ReviewResult(false, 0.78,
                    List.of(new ReviewResult.Issue("DEPTH_SUGGESTION", "MEDIUM", "可进一步补充实现细节")));

            assertTrue(harness.nodes.researchReview(NodeHarness.TASK_ID));
            ReviewResult persisted = harness.store.load(NodeHarness.TASK_ID).getReviewResults().get("research:" + item.id());
            assertTrue(persisted.passed());
            assertEquals("MEDIUM", persisted.issues().getFirst().severity());
        }
    }

    /** 构造一个最小且含真实问题列表的研究结果，使节点结构校验与工件写入均走生产路径。 */
    private ResearchResult research(PlanItem item, String question) {
        return new ResearchResult(item.id(), item.title(), List.of(
                new ResearchResult.ResearchDetail("detail-1", "核心知识", List.of(question))));
    }

    /** 创建一个具备标题、摘要和正文的最小合法答案，用于验证 Answer Repair 的完整入参链路。 */
    private Answer answer(PlanItem item, String content) {
        return new Answer(item.id(), item.title(), "测试摘要", List.of(new Answer.Section("核心说明", content)));
    }

    /** 创建单主题锁定 Plan，聚焦验证审核节点而不是无关的多主题并行行为。 */
    private PlanItem topic() {
        return new PlanItem("topic-1", "事务管理", "掌握事务传播与隔离边界", 1, true, "深入");
    }

    /**
     * 为每个测试装配独立的真实状态仓库和节点依赖。
     *
     * <p>研究修复仍通过 {@link TaskWorkflowNodes#parallel(List, java.util.function.Consumer)} 异步执行，
     * 因而保留单线程执行器，既覆盖并行入口又使断言中的替身记录稳定可读。</p>
     */
    private static final class NodeHarness implements AutoCloseable {
        /** 固定任务标识使测试可以直接读取预期状态文件。 */
        private static final String TASK_ID = "task-review-repair";
        /** 单线程已足够覆盖单主题 Repair 的异步等待语义。 */
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        /** 可记录 Repair 入参的确定性模型替身。 */
        private final RecordingLlm llm = new RecordingLlm();
        /** 真实状态仓库确保测试覆盖 JSON 持久化与中间工件落盘。 */
        private final TaskStateStore store;
        /** 被测工作流业务节点。 */
        private final TaskWorkflowNodes nodes;

        /** 初始化隔离配置、状态文件以及被测节点。 */
        private NodeHarness(Path temp) throws Exception {
            AgentProperties properties = new AgentProperties();
            properties.getStorage().setRoot(temp.resolve("data").toString());
            properties.getStorage().setAnswerRoot(temp.resolve("answer").toString());
            store = new TaskStateStore(properties);
            TaskEventService events = new TaskEventService(new EventLogStore(store));
            FileNameSanitizer sanitizer = new FileNameSanitizer();
            nodes = new TaskWorkflowNodes(store, events, llm, properties, executor,
                    new OutputDirectoryManager(properties, sanitizer), sanitizer, new MarkdownWriter());
            store.create(AgentState.created(TASK_ID, "测试事务管理"));
        }

        /** 将任务置为已锁定、已有研究结果和可选审核结果的状态，模拟进入 Repair/Review 前的持久化快照。 */
        private void prepare(PlanItem item, ResearchResult research, ReviewResult review) {
            Plan plan = new Plan(1, "测试纲要", "验证审核修复数据流", List.of(item));
            store.update(TASK_ID, state -> {
                state.setCurrentPlan(plan);
                state.setPlanLocked(true);
                state.getResearchResults().put(item.id(), research);
                if (review != null) state.getReviewResults().put("research:" + item.id(), review);
            });
        }

        /** 将任务置为已有研究依据、失败答案审核和待修复答案的状态，模拟 Answer Repair 入口。 */
        private void prepareAnswer(PlanItem item, ResearchResult research, Answer answer, ReviewResult review) {
            Plan plan = new Plan(1, "测试纲要", "验证审核修复数据流", List.of(item));
            store.update(TASK_ID, state -> {
                state.setCurrentPlan(plan);
                state.setPlanLocked(true);
                state.getResearchResults().put(item.id(), research);
                state.getAnswers().put(item.id(), answer);
                state.getReviewResults().put("answer:" + item.id(), review);
            });
        }

        /** 关闭测试线程，避免 Maven 测试进程因非守护线程无法退出。 */
        @Override public void close() { executor.shutdownNow(); }
    }

    /**
     * 最小 LLM 替身：除本次关心的研究审核与修复外，其他方法仅提供不会参与当前测试的确定性返回。
     * 字段保留 Repair 收到的参数，使测试能断言反馈链路没有在工作流中丢失。
     */
    private static final class RecordingLlm implements LlmService {
        /** Repair 节点实际传入的上一版研究结果。 */
        private ResearchResult previousResearch;
        /** Repair 节点实际传入的审核问题列表。 */
        private List<ReviewResult.Issue> researchRepairIssues = List.of();
        /** 测试指定的研究修复结果。 */
        private ResearchResult repairedResearch;
        /** Repair 节点实际传入的上一版答案。 */
        private Answer previousAnswer;
        /** Answer Repair 节点实际传入的审核问题列表。 */
        private List<ReviewResult.Issue> answerRepairIssues = List.of();
        /** 测试指定的答案修复结果。 */
        private Answer repairedAnswer;
        /** 测试指定的研究审核结果，默认通过以避免无关失败。 */
        private ReviewResult researchReview = new ReviewResult(true, 1.0, List.of());

        /** 当前测试不覆盖任务分类，返回固定枚举保持接口完整。 */
        @Override public TaskAnalysis analyze(String question) { return new TaskAnalysis("LEARNING", "GENERAL", "测试"); }
        /** 当前测试不覆盖规划，返回单主题空纲要即可。 */
        @Override public Plan draftPlan(String question) { return new Plan(1, "测试", "测试", List.of()); }
        /** 当前测试不覆盖规划修订，直接复用当前版本。 */
        @Override public Plan revisePlan(String question, Plan current, String feedback) { return current; }
        /** 当前测试只从已有状态进入 Repair，不应调用普通研究；若调用则明确暴露回退错误。 */
        @Override public ResearchResult research(String question, Plan plan, PlanItem item) { throw new AssertionError("不应调用普通研究"); }
        /** 记录全部定向修复输入，验证审核意见不会在工作流边界丢失。 */
        @Override public ResearchResult repairResearch(String question, Plan plan, PlanItem item, ResearchResult previous,
                                                       List<ReviewResult.Issue> issues) {
            previousResearch = previous;
            researchRepairIssues = issues;
            return repairedResearch;
        }
        /** 返回测试预设审核结论，专门覆盖通过/修复路由。 */
        @Override public ReviewResult reviewResearch(Plan plan, PlanItem item, ResearchResult result) { return researchReview; }
        /** 当前测试不覆盖答案生成，提供最小合法答案以满足接口。 */
        @Override public Answer generateAnswer(Plan plan, PlanItem item, ResearchResult research) {
            return new Answer(item.id(), item.title(), "测试", List.of(new Answer.Section("正文", "测试")));
        }
        /** 记录答案定向修复输入，验证答案审核意见不会在 Repair 边界丢失。 */
        @Override public Answer repairAnswer(Plan plan, PlanItem item, ResearchResult research, Answer previous,
                                             List<ReviewResult.Issue> issues) {
            previousAnswer = previous;
            answerRepairIssues = issues;
            return repairedAnswer;
        }
        /** 当前测试不覆盖答案审核，固定通过。 */
        @Override public ReviewResult reviewAnswer(PlanItem item, Answer answer) { return new ReviewResult(true, 1.0, List.of()); }
        /** 当前测试不覆盖标题生成，返回固定标题。 */
        @Override public String generateTitle(String question, Plan plan) { return "测试"; }
    }
}
