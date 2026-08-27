package com.example.agent.controller;

import com.example.agent.api.AcceptedTaskResponse;
import com.example.agent.api.ConfirmPlanRequest;
import com.example.agent.api.CreateTaskRequest;
import com.example.agent.api.TaskMessageRequest;
import com.example.agent.model.AgentState;
import com.example.agent.model.Plan;
import com.example.agent.model.PlanVersion;
import com.example.agent.model.Task;
import com.example.agent.service.TaskEventService;
import com.example.agent.service.TaskService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 任务、Plan、人机对话与 SSE 的 REST 接口。 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService taskService;
    private final TaskEventService events;

    public TaskController(TaskService taskService, TaskEventService events) { this.taskService = taskService; this.events = events; }

    @PostMapping
    public ResponseEntity<AcceptedTaskResponse> create(@Valid @RequestBody CreateTaskRequest request) {
        AgentState state = taskService.create(request.question());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new AcceptedTaskResponse(state.getTaskId(), state.getStatus().name()));
    }

    /** 历史列表仅返回 Task 摘要，详情页再按 taskId 请求完整状态。 */
    @GetMapping public List<Task> list() { return taskService.list().stream().map(Task::from).toList(); }
    @GetMapping("/{taskId}") public AgentState get(@PathVariable String taskId) { return taskService.get(taskId); }
    @GetMapping("/{taskId}/plan") public Plan plan(@PathVariable String taskId) { return taskService.get(taskId).getCurrentPlan(); }
    @GetMapping("/{taskId}/plan/versions") public List<PlanVersion> versions(@PathVariable String taskId) { return taskService.get(taskId).getPlanVersions(); }

    @PostMapping("/{taskId}/messages")
    public ResponseEntity<AcceptedTaskResponse> message(@PathVariable String taskId, @Valid @RequestBody TaskMessageRequest request) {
        taskService.revisePlan(taskId, request.message());
        return ResponseEntity.accepted().body(new AcceptedTaskResponse(taskId, "PLAN_REVISING"));
    }

    @PostMapping("/{taskId}/plan/confirm")
    public ResponseEntity<AcceptedTaskResponse> confirm(@PathVariable String taskId, @Valid @RequestBody ConfirmPlanRequest request) {
        taskService.confirmPlan(taskId, request.planVersion());
        return ResponseEntity.accepted().body(new AcceptedTaskResponse(taskId, "PLAN_CONFIRMED"));
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<AcceptedTaskResponse> cancel(@PathVariable String taskId) {
        AgentState state = taskService.cancel(taskId);
        return ResponseEntity.accepted().body(new AcceptedTaskResponse(taskId, state.getStatus().name()));
    }

    /** SSE 支持 Last-Event-ID 标准头，也支持 afterId 查询参数以兼容 EventSource 重连。 */
    @GetMapping("/{taskId}/events")
    public SseEmitter events(@PathVariable String taskId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             @RequestParam(value = "afterId", required = false) Long afterId) {
        long cursor = afterId != null ? afterId : parse(lastEventId);
        return events.connect(taskService.get(taskId), cursor);
    }

    private long parse(String source) {
        try { return source == null || source.isBlank() ? 0L : Long.parseLong(source); }
        catch (NumberFormatException ex) { return 0L; }
    }
}
