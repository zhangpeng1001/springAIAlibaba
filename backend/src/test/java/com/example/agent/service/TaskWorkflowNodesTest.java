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

/** 验证逐项答案直接生成、主题 ID 校验和中间答案工件持久化。 */
class TaskWorkflowNodesTest {
    @TempDir Path temp;

    @Test
    void generatesDetailedAnswersDirectlyFromPlanItems() throws Exception {
        try (Harness harness = new Harness(temp)) {
            PlanItem item = new PlanItem("topic-1", "事务管理", "掌握事务传播与隔离边界", 1, true, "深入");
            Plan plan = new Plan(1, "测试纲要", "验证线性答案生成", List.of(item));
            harness.store.update("task-test", state -> state.setCurrentPlan(plan));

            harness.nodes.answerGenerate("task-test");

            AgentState state = harness.store.load("task-test");
            assertEquals("topic-1", state.getAnswers().get("topic-1").topicId());
            assertTrue(java.nio.file.Files.exists(harness.store.taskDir("task-test").resolve("answers/topic-1.json")));
        }
    }

    /** 组装真实状态仓库和文件依赖，模型替身只实现新线性接口。 */
    private static final class Harness implements AutoCloseable {
        private final ExecutorService executor = Executors.newFixedThreadPool(2);
        private final TaskStateStore store;
        private final TaskWorkflowNodes nodes;

        private Harness(Path temp) throws Exception {
            AgentProperties properties = new AgentProperties();
            properties.getStorage().setRoot(temp.resolve("data").toString());
            properties.getStorage().setAnswerRoot(temp.resolve("answer").toString());
            store = new TaskStateStore(properties);
            TaskEventService events = new TaskEventService(new EventLogStore(store));
            FileNameSanitizer sanitizer = new FileNameSanitizer();
            nodes = new TaskWorkflowNodes(store, events, new RecordingLlm(), properties, executor,
                    new OutputDirectoryManager(properties, sanitizer), sanitizer, new MarkdownWriter());
            store.create(AgentState.created("task-test", "测试问题"));
        }

        @Override public void close() { executor.shutdownNow(); }
    }

    /** 返回确定性结构化答案，确保测试只验证编排和持久化而不依赖网络。 */
    private static final class RecordingLlm implements LlmService {
        @Override public TaskAnalysis analyze(String question) { return new TaskAnalysis("LEARNING", "GENERAL", "测试"); }
        @Override public Plan draftPlan(String question) { return new Plan(1, "测试", "测试", List.of()); }
        @Override public Answer generateAnswer(String question, Plan plan, PlanItem item) {
            return new Answer(item.id(), item.title(), "测试摘要", List.of(new Answer.Section("正文", "测试详细解答")));
        }
        @Override public String generateTitle(String question, Plan plan) { return "测试"; }
    }
}
