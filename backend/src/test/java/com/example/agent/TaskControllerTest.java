package com.example.agent;

import com.example.agent.api.CreateTaskRequest;
import com.example.agent.controller.TaskController;
import com.example.agent.model.AgentState;
import com.example.agent.service.TaskEventService;
import com.example.agent.service.TaskService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controller 单测验证创建接口采用异步受理语义，而不会阻塞等待 LLM。 */
class TaskControllerTest {
    @Test
    void createReturnsAcceptedReceipt() {
        TaskService tasks = mock(TaskService.class);
        when(tasks.create("测试问题")).thenReturn(AgentState.created("task-api", "测试问题"));
        TaskController controller = new TaskController(tasks, mock(TaskEventService.class));
        var response = controller.create(new CreateTaskRequest("测试问题"));
        assertEquals(202, response.getStatusCode().value());
        assertEquals("task-api", response.getBody().taskId());
    }
}
