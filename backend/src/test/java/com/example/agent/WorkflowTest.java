package com.example.agent;

import com.example.agent.config.AgentProperties;
import com.example.agent.file.FileNameSanitizer;
import com.example.agent.file.MarkdownWriter;
import com.example.agent.file.OutputDirectoryManager;
import com.example.agent.llm.TemplateLlmService;
import com.example.agent.model.AgentState;
import com.example.agent.model.TaskStatus;
import com.example.agent.persistence.EventLogStore;
import com.example.agent.persistence.TaskStateStore;
import com.example.agent.service.TaskEventService;
import com.example.agent.service.TaskService;
import com.example.agent.service.TaskWorkflowNodes;
import com.example.agent.workflow.AgentWorkflow;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端 Workflow 测试：验证 Human Gate、Plan 版本、锁定后自动生成及 Markdown 工件。
 * 使用离线模型让测试不依赖 API Key 或外部网络。
 */
class WorkflowTest {
    @TempDir java.nio.file.Path temp;

    @Test
    void waitsForExplicitConfirmationThenGeneratesFiles() throws Exception {
        try (Harness harness = new Harness(temp)) {
            AgentState created = harness.tasks.create("如何学习 Java？");
            long initialDeadline = System.currentTimeMillis() + 8000;
            while (harness.tasks.get(created.getTaskId()).getStatus() != TaskStatus.WAITING_USER_PLAN && System.currentTimeMillis() < initialDeadline) Thread.sleep(25);
            AgentState planned = harness.tasks.get(created.getTaskId());
            assertEquals(TaskStatus.WAITING_USER_PLAN, planned.getStatus(), planned.getErrorCode() + ": " + planned.getErrorMessage());
            assertFalse(harness.tasks.get(created.getTaskId()).isPlanLocked());

            harness.tasks.revisePlan(created.getTaskId(), "增加 Docker");
            await(() -> harness.tasks.get(created.getTaskId()).getPlanVersion() == 2 && harness.tasks.get(created.getTaskId()).getStatus() == TaskStatus.WAITING_USER_PLAN);
            assertEquals(1, harness.tasks.get(created.getTaskId()).getPlanVersions().getFirst().plan().version());

            harness.tasks.confirmPlan(created.getTaskId(), 2);
            long deadline = System.currentTimeMillis() + 8000;
            while (harness.tasks.get(created.getTaskId()).getStatus() != TaskStatus.SUCCESS && System.currentTimeMillis() < deadline) Thread.sleep(25);
            AgentState observed = harness.tasks.get(created.getTaskId());
            assertEquals(TaskStatus.SUCCESS, observed.getStatus(), observed.getErrorCode() + ": " + observed.getErrorMessage());
            AgentState finished = harness.tasks.get(created.getTaskId());
            assertTrue(finished.isPlanLocked());
            assertTrue(Files.exists(java.nio.file.Path.of(finished.getOutputDirectory()).resolve("README.md")));
            assertTrue(finished.getOutputFiles().contains("metadata.json"));
        }
    }

    /** 已锁定但尚未完成的任务在“重启”扫描后应从自动阶段继续，而非退回 Human Gate。 */
    @Test
    void recoversLockedTaskFromPersistedState() throws Exception {
        try (Harness harness = new Harness(temp)) {
            AgentState created = harness.tasks.create("如何学习并发？");
            await(() -> harness.tasks.get(created.getTaskId()).getStatus() == TaskStatus.WAITING_USER_PLAN);
            harness.states.update(created.getTaskId(), state -> {
                state.setPlanConfirmed(true);
                state.setPlanLocked(true);
                state.setStatus(TaskStatus.RESEARCHING);
                state.setCurrentNode("RESEARCH");
            });
            harness.tasks.recoverIncompleteTasks();
            await(() -> harness.tasks.get(created.getTaskId()).getStatus() == TaskStatus.SUCCESS);
            assertTrue(harness.tasks.get(created.getTaskId()).isPlanLocked());
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "工作流没有在规定时间内达到预期状态");
    }

    /** 手动装配最小运行环境，既验证 StateGraph，又避免 Spring 测试上下文共享真实数据目录。 */
    private static final class Harness implements AutoCloseable {
        private final ExecutorService executor = Executors.newFixedThreadPool(4);
        private final TaskService tasks;
        private final TaskStateStore states;
        private Harness(java.nio.file.Path temp) throws Exception {
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
