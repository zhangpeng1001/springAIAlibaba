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

/**
 * 任务、Plan、人机对话与 SSE 的 REST 接口。
 *
 * <p>Controller 不承载工作流判断或 LLM 调用，只负责 HTTP 校验、异步受理和返回当前真实状态；
 * 这样浏览器刷新、SSE 断线与后台执行不会改变业务规则。</p>
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    /** 任务生命周期门面，集中执行状态校验和异步调度。 */
    private final TaskService taskService;
    /** SSE 连接及历史回放服务。 */
    private final TaskEventService events;

    /** @param taskService 任务领域服务 @param events SSE 事件服务 */
    public TaskController(TaskService taskService, TaskEventService events) { this.taskService = taskService; this.events = events; }

    /**
     * 异步创建任务。
     *
     * @param request 用户问题；Bean Validation 已在进入方法前校验空值和最大长度
     * @return 202 与 taskId；前端应继续订阅 SSE 或查询详情，不能假设 Plan 已同步生成
     */
    @PostMapping
    public ResponseEntity<AcceptedTaskResponse> create(@Valid @RequestBody CreateTaskRequest request) {
        AgentState state = taskService.create(request.question());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new AcceptedTaskResponse(state.getTaskId(), state.getStatus().name()));
    }

    /** 历史列表仅返回 Task 摘要，避免首页下载研究和答案全文。 */
    @GetMapping public List<Task> list() { return taskService.list().stream().map(Task::from).toList(); }

    /** @return 当前 state.json 的完整任务快照，供任务详情页恢复 UI。 */
    @GetMapping("/{taskId}") public AgentState get(@PathVariable String taskId) { return taskService.get(taskId); }

    /** @return 当前可见或已锁定的 Plan；不存在时返回 null 代表仍在生成初稿。 */
    @GetMapping("/{taskId}/plan") public Plan plan(@PathVariable String taskId) { return taskService.get(taskId).getCurrentPlan(); }

    /** @return 从 V1 到当前版本的不可变 Plan 审计列表。 */
    @GetMapping("/{taskId}/plan/versions") public List<PlanVersion> versions(@PathVariable String taskId) { return taskService.get(taskId).getPlanVersions(); }

    /**
     * 接收用户修改意见并异步触发 Plan 修订。
     * 自然语言中包含“确认”也只当作一条意见，不能绕过显式确认接口。
     */
    @PostMapping("/{taskId}/messages")
    public ResponseEntity<AcceptedTaskResponse> message(@PathVariable String taskId, @Valid @RequestBody TaskMessageRequest request) {
        taskService.revisePlan(taskId, request.message());
        return ResponseEntity.accepted().body(new AcceptedTaskResponse(taskId, "PLAN_REVISING"));
    }

    /**
     * 显式确认浏览器正在展示的 Plan 版本。
     * 若版本已经被另一条意见更新，TaskService 会拒绝请求而不是锁定过期内容。
     */
    @PostMapping("/{taskId}/plan/confirm")
    public ResponseEntity<AcceptedTaskResponse> confirm(@PathVariable String taskId, @Valid @RequestBody ConfirmPlanRequest request) {
        taskService.confirmPlan(taskId, request.planVersion());
        return ResponseEntity.accepted().body(new AcceptedTaskResponse(taskId, "PLAN_CONFIRMED"));
    }

    /**
     * 请求协作式取消。已开始的远程模型调用不会被粗暴中断，但不会再推进到后续节点。
     */
    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<AcceptedTaskResponse> cancel(@PathVariable String taskId) {
        AgentState state = taskService.cancel(taskId);
        return ResponseEntity.accepted().body(new AcceptedTaskResponse(taskId, state.getStatus().name()));
    }

    /**
     * SSE 支持 Last-Event-ID 标准头，也支持 afterId 查询参数以兼容 EventSource 重连。
     * 
     * @return 先发送 TASK_SNAPSHOT，再发送游标之后的 JSONL 历史事件和实时事件
     */
    @GetMapping("/{taskId}/events")
    public SseEmitter events(@PathVariable String taskId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             @RequestParam(value = "afterId", required = false) Long afterId) {
        long cursor = afterId != null ? afterId : parse(lastEventId);
        return events.connect(taskService.get(taskId), cursor);
    }

    /**
     * 解析浏览器传来的事件游标。
     * 非法游标回退为 0，优先保证客户端能获得完整回放而不是因单个坏请求无法观察任务。
     */
    private long parse(String source) {
        try { return source == null || source.isBlank() ? 0L : Long.parseLong(source); }
        catch (NumberFormatException ex) { return 0L; }
    }
}
