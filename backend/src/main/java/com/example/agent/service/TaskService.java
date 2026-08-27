package com.example.agent.service;

import com.example.agent.exception.TaskException;
import com.example.agent.model.AgentState;
import com.example.agent.model.TaskStatus;
import com.example.agent.persistence.TaskStateStore;
import com.example.agent.workflow.AgentWorkflow;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;

/**
 * 面向 Controller 的任务门面。
 * 该类只处理 API 状态校验、异步提交和失败兜底；具体语言任务全部委托给 StateGraph 节点。
 */
@Service
public class TaskService {
    private final TaskStateStore stateStore;
    private final TaskEventService events;
    private final AgentWorkflow workflow;
    private final ExecutorService executor;

    public TaskService(TaskStateStore stateStore, TaskEventService events, AgentWorkflow workflow,
                       ExecutorService agentExecutor) {
        this.stateStore = stateStore;
        this.events = events;
        this.workflow = workflow;
        this.executor = agentExecutor;
    }

    /** 创建任务并异步运行“分析 → 初始纲要 → 等待人工”的图分支。 */
    public AgentState create(String question) {
        String taskId = createTaskId();
        AgentState state = AgentState.created(taskId, question.trim());
        stateStore.create(state);
        events.publish(taskId, "TASK_CREATED", "START", Map.of("question", state.getQuestion()));
        submit(taskId, "INITIAL");
        return state;
    }

    public AgentState get(String taskId) { return load(taskId); }

    /** 返回最近更新时间倒序的任务列表，供首页历史任务区域展示。 */
    public List<AgentState> list() {
        return stateStore.list().stream().sorted(Comparator.comparing(AgentState::getUpdatedAt).reversed()).toList();
    }

    /**
     * 保存待处理意见并触发 REVISE 图分支。
     * 自然语言中即使出现“确认”也不会锁定 Plan，确认只能走 confirmPlan 接口。
     */
    public void revisePlan(String taskId, String message) {
        AgentState state = stateStore.update(taskId, s -> {
            require(s.getStatus() == TaskStatus.WAITING_USER_PLAN && !s.isPlanLocked() && !s.isPlanConfirmed(), "PLAN_REVISION_FAILED", "当前任务不处于可修改纲要状态");
            s.setPendingPlanFeedback(message.trim());
            s.setStatus(TaskStatus.PLAN_REVISING);
        });
        events.publish(taskId, "PLAN_REVISION_RECEIVED", "PLAN_REVISE", Map.of("message", message.trim(), "planVersion", state.getPlanVersion()));
        submit(taskId, "REVISE");
    }

    /**
     * 校验客户端看到的 Plan 版本后记录显式确认，再触发自动阶段。
     * 不在本方法直接调用 LLM，保证 HTTP 请求快速返回 202。
     */
    public void confirmPlan(String taskId, int planVersion) {
        AgentState state = stateStore.update(taskId, s -> {
            require(s.getStatus() == TaskStatus.WAITING_USER_PLAN, "PLAN_CONFIRM_FAILED", "当前任务不在等待纲要确认状态");
            require(s.getCurrentPlan() != null, "PLAN_CONFIRM_FAILED", "当前纲要不存在");
            require(s.getPlanVersion() == planVersion && s.getCurrentPlan().version() == planVersion,
                    "PLAN_CONFIRM_FAILED", "确认的纲要版本已过期");
            s.setPlanConfirmed(true);
        });
        events.publish(taskId, "PLAN_CONFIRMED", "PLAN_CONFIRM", Map.of("planVersion", state.getPlanVersion()));
        submit(taskId, "AUTO");
    }

    /** 协作式取消：运行中的节点会在开始、模型返回与并行汇总点检查该标记。 */
    public AgentState cancel(String taskId) {
        AgentState state = stateStore.update(taskId, s -> {
            if (s.getStatus() == TaskStatus.SUCCESS || s.getStatus() == TaskStatus.FAILED || s.getStatus() == TaskStatus.CANCELLED) {
                throw new TaskException("TASK_INVALID", "终态任务不能取消");
            }
            s.setCancelRequested(true);
            s.setStatus(TaskStatus.CANCELLED);
            s.setCurrentNode("CANCELLED");
        });
        events.publish(taskId, "WORKFLOW_CANCELLED", "CANCELLED", Map.of());
        return state;
    }

    /** 服务启动恢复：人工等待态保持等待；所有自动态从最近落盘阶段重新进入相应图分支。 */
    public void recoverIncompleteTasks() {
        for (AgentState state : stateStore.list()) {
            if (state.getStatus() == TaskStatus.WAITING_USER_PLAN || state.getStatus() == TaskStatus.SUCCESS
                    || state.getStatus() == TaskStatus.FAILED || state.getStatus() == TaskStatus.CANCELLED) continue;
            String mode = state.getStatus() == TaskStatus.PLAN_REVISING ? "REVISE"
                    : state.isPlanConfirmed() || state.isPlanLocked() ? "AUTO" : "INITIAL";
            events.publish(state.getTaskId(), "TASK_RECOVERY_STARTED", "RECOVERY", Map.of("mode", mode));
            submit(state.getTaskId(), mode);
        }
    }

    private void submit(String taskId, String mode) {
        executor.submit(() -> {
            try {
                workflow.run(taskId, mode);
            } catch (Exception ex) {
                fail(taskId, ex);
            }
        });
    }

    private void fail(String taskId, Exception ex) {
        AgentState latest;
        try { latest = stateStore.load(taskId); } catch (Exception ignored) { return; }
        if (latest.getStatus() == TaskStatus.CANCELLED || latest.isCancelRequested()) return;
        String code = ex instanceof TaskException taskException ? taskException.getCode() : "UNKNOWN_ERROR";
        // 保留框架异常的摘要，便于本地状态文件和前端错误面板定位 Graph/IO 故障；
        // 真实模型响应正文不在这里写入，避免把潜在敏感提示词落盘。
        String message = ex instanceof TaskException ? ex.getMessage()
                : "工作流执行失败" + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
        AgentState failed = stateStore.update(taskId, s -> {
            s.setStatus(TaskStatus.FAILED);
            s.setErrorCode(code);
            s.setErrorMessage(message);
        });
        events.publish(taskId, "TASK_FAILED", failed.getCurrentNode(), Map.of("errorCode", code, "message", message));
    }

    private AgentState load(String taskId) {
        try { return stateStore.load(taskId); }
        catch (IllegalArgumentException ex) { throw new TaskException("TASK_NOT_FOUND", "任务不存在: " + taskId); }
    }

    private String createTaskId() {
        return "task-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    private void require(boolean condition, String code, String message) { if (!condition) throw new TaskException(code, message); }
}
