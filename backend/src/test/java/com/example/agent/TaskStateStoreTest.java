package com.example.agent;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.AgentState;
import com.example.agent.model.TaskStatus;
import com.example.agent.persistence.TaskStateStore;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 验证状态可创建、原子更新并可从 JSON 恢复。 */
class TaskStateStoreTest {
    @TempDir java.nio.file.Path temp;

    @Test
    void savesAndReloadsState() throws Exception {
        AgentProperties properties = properties();
        TaskStateStore store = new TaskStateStore(properties);
        store.create(AgentState.created("task-test", "测试问题"));
        store.update("task-test", state -> state.setStatus(TaskStatus.WAITING_USER_PLAN));
        assertEquals(TaskStatus.WAITING_USER_PLAN, store.load("task-test").getStatus());
        assertTrue(Files.exists(store.taskDir("task-test").resolve("state.json")));
    }

    private AgentProperties properties() {
        AgentProperties properties = new AgentProperties();
        properties.getStorage().setRoot(temp.resolve("data").toString());
        properties.getStorage().setAnswerRoot(temp.resolve("answer").toString());
        return properties;
    }
}
