package com.example.agent;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.AgentState;
import com.example.agent.persistence.EventLogStore;
import com.example.agent.persistence.TaskStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 验证事件编号单调递增且 after 游标能正确回放遗漏事件。 */
class EventLogStoreTest {
    @TempDir java.nio.file.Path temp;

    @Test
    void appendsIncrementingEventsAndReplaysAfterCursor() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getStorage().setRoot(temp.resolve("data").toString());
        TaskStateStore stateStore = new TaskStateStore(properties);
        stateStore.create(AgentState.created("task-events", "测试"));
        EventLogStore events = new EventLogStore(stateStore);
        assertEquals(1, events.append("task-events", "ONE", "TEST", java.util.Map.of()).eventId());
        assertEquals(2, events.append("task-events", "TWO", "TEST", java.util.Map.of()).eventId());
        assertEquals(1, events.after("task-events", 1).size());
        assertEquals("TWO", events.after("task-events", 1).getFirst().type());
    }
}
