package com.example.agent;

import com.example.agent.config.AgentProperties;
import com.example.agent.file.FileNameSanitizer;
import com.example.agent.file.MarkdownWriter;
import com.example.agent.file.OutputDirectoryManager;
import com.example.agent.llm.TemplateLlmService;
import com.example.agent.model.AgentState;
import com.example.agent.model.TaskStatus;
import com.example.agent.model.Answer;
import com.example.agent.model.Plan;
import com.example.agent.model.PlanItem;
import com.example.agent.persistence.EventLogStore;
import com.example.agent.persistence.TaskStateStore;
import com.example.agent.service.TaskEventService;
import com.example.agent.service.TaskService;
import com.example.agent.service.TaskWorkflowNodes;
import com.example.agent.workflow.AgentWorkflow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端到端验证线性流程无需人工确认即可生成纲要、答案和最终 Markdown 文件。 */
class WorkflowTest {
    @TempDir Path temp;

    @Test
    void automaticallyGeneratesFilesAfterInitialOutline() throws Exception {
        try (Harness harness = new Harness(temp)) {
            AgentState created = harness.tasks.create("如何学习 Java？");
            await(() -> harness.tasks.get(created.getTaskId()).getStatus() == TaskStatus.SUCCESS);
            AgentState finished = harness.tasks.get(created.getTaskId());
            assertEquals(TaskStatus.SUCCESS, finished.getStatus(), finished.getErrorCode() + ": " + finished.getErrorMessage());
            assertFalse(finished.getAnswers().isEmpty());
            assertTrue(Files.exists(Path.of(finished.getOutputDirectory()).resolve("README.md")));
            assertTrue(finished.getOutputFiles().contains("metadata.json"));
        }
    }

    /** 已落盘的答案阶段任务恢复时从答案节点继续，不能重复要求人工确认。 */
    @Test
    void recoversFromPersistedStage() throws Exception {
        try (Harness harness = new Harness(temp)) {
            AgentState created = AgentState.created("task-recovery", "如何学习并发？");
            PlanItem item = new PlanItem("topic-1", "并发基础", "线程与锁", 1, true, "NORMAL");
            created.setTaskType("LEARNING");
            created.setCurrentPlan(new Plan(1, "并发", "掌握并发", java.util.List.of(item)));
            created.getAnswers().put(item.id(), new Answer(item.id(), item.title(), "摘要",
                    java.util.List.of(new Answer.Section("正文", "已生成"))));
            created.setStatus(TaskStatus.TITLE_GENERATING);
            created.setCurrentNode("TITLE_GENERATE");
            harness.states.create(created);
            harness.tasks.recoverIncompleteTasks();
            await(() -> harness.tasks.get(created.getTaskId()).getStatus() == TaskStatus.SUCCESS);
            assertEquals(TaskStatus.SUCCESS, harness.tasks.get(created.getTaskId()).getStatus());
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "工作流没有在规定时间内达到预期状态");
    }

    /** 手动装配独立状态目录，覆盖真实 StateGraph 和本地文件输出。 */
    private static final class Harness implements AutoCloseable {
        private final ExecutorService executor = Executors.newFixedThreadPool(4);
        private final TaskService tasks;
        private final TaskStateStore states;

        private Harness(Path temp) throws Exception {
            AgentProperties properties = new AgentProperties();
            properties.getStorage().setRoot(temp.resolve("data").toString());
            properties.getStorage().setAnswerRoot(temp.resolve("answer").toString());
            states = new TaskStateStore(properties);
            TaskEventService events = new TaskEventService(new EventLogStore(states));
            FileNameSanitizer sanitizer = new FileNameSanitizer();
            TaskWorkflowNodes nodes = new TaskWorkflowNodes(states, events, new TemplateLlmService(), properties, executor,
                    new OutputDirectoryManager(properties, sanitizer), sanitizer, new MarkdownWriter());
            tasks = new TaskService(states, events, new AgentWorkflow(nodes), executor);
        }

        @Override public void close() { executor.shutdownNow(); }
    }
}
