package com.example.agent.controller;

import com.example.agent.api.AcceptedTaskResponse;
import com.example.agent.api.CreateTaskRequest;
import com.example.agent.model.AgentState;
import com.example.agent.model.Plan;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务、只读 Plan 与 SSE 的 REST 接口。
 *
 * <p>Controller 不承载工作流判断或 LLM 调用，只负责 HTTP 校验、异步受理和返回当前真实状态；
 * 这样浏览器刷新、SSE 断线与后台执行不会改变业务规则。</p>
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    /**
     * HTTP 入口日志。
     *
     * <p>请求正文可能包含用户隐私，因此仅记录动作、taskId、版本和文本长度；具体的后台执行轨迹
     * 由 TaskService、AgentWorkflow 与 TaskEventService 继续记录。</p>
     */
    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

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
     * @return 202 与 taskId；前端应继续订阅 SSE 或查询详情，不能假设最终文件已同步生成
     */
    @PostMapping
    public ResponseEntity<AcceptedTaskResponse> create(@Valid @RequestBody CreateTaskRequest request) {
        AgentState state = taskService.create(request.question());
        log.info("已受理创建任务请求：taskId={}，questionLength={}，initialStatus={}", state.getTaskId(),
                request.question().length(), state.getStatus());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new AcceptedTaskResponse(state.getTaskId(), state.getStatus().name()));
    }

    /** 历史列表仅返回 Task 摘要，避免首页下载答案全文。 */
    @GetMapping public List<Task> list() {
        List<Task> tasks = taskService.list().stream().map(Task::from).toList();
        log.info("已查询任务列表：count={}", tasks.size());
        return tasks;
    }

    /** @return 当前 state.json 的完整任务快照，供任务详情页恢复 UI。 */
    @GetMapping("/{taskId}") public AgentState get(@PathVariable String taskId) {
        AgentState state = taskService.get(taskId);
        log.info("已查询任务详情：taskId={}，status={}，currentNode={}", taskId, state.getStatus(), state.getCurrentNode());
        return state;
    }

    /** @return 当前自动生成的初始 Plan；不存在时返回 null 代表仍在生成初稿。 */
    @GetMapping("/{taskId}/plan") public Plan plan(@PathVariable String taskId) {
        Plan plan = taskService.get(taskId).getCurrentPlan();
        log.info("已查询当前纲要：taskId={}，planVersion={}，exists={}", taskId, plan == null ? null : plan.version(), plan != null);
        return plan;
    }

    /**
     * 请求协作式取消。已开始的远程模型调用不会被粗暴中断，但不会再推进到后续节点。
     */
    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<AcceptedTaskResponse> cancel(@PathVariable String taskId) {
        AgentState state = taskService.cancel(taskId);
        log.info("已受理取消任务：taskId={}，status={}", taskId, state.getStatus());
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
        log.info("正在建立 SSE 订阅：taskId={}，afterEventId={}", taskId, cursor);
        return events.connect(taskService.get(taskId), cursor);
    }

    /**
     * 解析浏览器传来的事件游标。
     * 非法游标回退为 0，优先保证客户端能获得完整回放而不是因单个坏请求无法观察任务。
     */
    private long parse(String source) {
        try { return source == null || source.isBlank() ? 0L : Long.parseLong(source); }
        catch (NumberFormatException ex) {
            log.warn("SSE 事件游标格式非法，已从头回放：source={}", source);
            return 0L;
        }
    }
}
