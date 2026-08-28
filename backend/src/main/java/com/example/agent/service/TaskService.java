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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 线性任务门面。
 * 该类只负责状态读取、异步调度、取消和失败兜底；所有模型与文件业务由 StateGraph 节点执行。
 */
@Service
public class TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private final TaskStateStore stateStore;
    private final TaskEventService events;
    private final AgentWorkflow workflow;
    private final ExecutorService executor;

    /** 注入状态仓库、事件服务、线性工作流和受限后台执行器。 */
    public TaskService(TaskStateStore stateStore, TaskEventService events, AgentWorkflow workflow, ExecutorService agentExecutor) {
        this.stateStore = stateStore;
        this.events = events;
        this.workflow = workflow;
        this.executor = agentExecutor;
    }

    /** 创建任务并立即提交线性异步流程；HTTP 调用只返回已落盘的任务标识。 */
    public AgentState create(String question) {
        String taskId = createTaskId();
        AgentState state = AgentState.created(taskId, question.trim());
        stateStore.create(state);
        events.publish(taskId, "TASK_CREATED", "START", Map.of("question", state.getQuestion()));
        submit(taskId, "TASK_ANALYZE");
        return state;
    }

    /** 读取最新任务快照，不使用内存缓存，保证查询结果与磁盘状态一致。 */
    public AgentState get(String taskId) { return load(taskId); }

    /** 返回按更新时间倒序排列的历史任务摘要。 */
    public List<AgentState> list() {
        return stateStore.list().stream().sorted(Comparator.comparing(AgentState::getUpdatedAt).reversed()).toList();
    }

    /** 协作式取消任务；正在进行的远程调用返回后会在安全边界停止。 */
    public AgentState cancel(String taskId) {
        AgentState state = stateStore.update(taskId, s -> {
            require(s.getStatus() != TaskStatus.SUCCESS && s.getStatus() != TaskStatus.FAILED && s.getStatus() != TaskStatus.CANCELLED,
                    "TASK_INVALID", "终态任务不能取消");
            s.setCancelRequested(true);
            s.setStatus(TaskStatus.CANCELLED);
            s.setCurrentNode("CANCELLED");
        });
        events.publish(taskId, "WORKFLOW_CANCELLED", "CANCELLED", Map.of());
        return state;
    }

    /**
     * 服务启动时恢复未完成的线性任务。
     * 已有产物的节点会幂等跳过；无法识别的旧状态被标记为明确的迁移失败。
     */
    public void recoverIncompleteTasks() {
        for (AgentState state : stateStore.list()) {
            if (state.getStatus() == TaskStatus.SUCCESS || state.getStatus() == TaskStatus.FAILED || state.getStatus() == TaskStatus.CANCELLED) continue;
            String entry = entryFor(state);
            if (entry == null) {
                markLegacyUnsupported(state.getTaskId());
                continue;
            }
            events.publish(state.getTaskId(), "TASK_RECOVERY_STARTED", "RECOVERY", Map.of("entry", entry));
            submit(state.getTaskId(), entry);
        }
    }

    /** 根据持久化状态选择首个尚未完成的线性节点。 */
    private String entryFor(AgentState state) {
        if (state.getStatus() == null) return null;
        return switch (state.getStatus()) {
            case CREATED, ANALYZING -> "TASK_ANALYZE";
            case PLAN_DRAFTING -> "PLAN_DRAFT";
            case ANSWER_GENERATING -> "ANSWER_GENERATE";
            case TITLE_GENERATING -> "TITLE_GENERATE";
            case FILE_GENERATING -> "FILE_GENERATE";
            case SUCCESS, FAILED, CANCELLED -> null;
        };
    }

    /** 将旧版状态转换为可观察失败，避免旧状态被新线性图错误解释。 */
    private void markLegacyUnsupported(String taskId) {
        AgentState failed = stateStore.update(taskId, s -> {
            s.setStatus(TaskStatus.FAILED);
            s.setErrorCode("LEGACY_WORKFLOW_UNSUPPORTED");
            s.setErrorMessage("该任务由旧版人工确认/审核流程创建，请重新提交问题");
        });
        events.publish(taskId, "TASK_FAILED", failed.getCurrentNode(), Map.of("errorCode", failed.getErrorCode(), "message", failed.getErrorMessage()));
    }

    /** 把线性 Graph 调用提交到后台，并统一固化未处理异常。 */
    private void submit(String taskId, String entry) {
        executor.submit(() -> {
            try {
                workflow.run(taskId, entry);
            } catch (Exception ex) {
                fail(taskId, ex);
            }
        });
    }

    /** 将异常转换为稳定错误码并发布失败事件；取消状态不会被晚到异常覆盖。 */
    private void fail(String taskId, Exception ex) {
        AgentState latest;
        try { latest = stateStore.load(taskId); }
        catch (Exception loadException) { log.error("工作流失败后无法读取状态：taskId={}", taskId, loadException); return; }
        if (latest.getStatus() == TaskStatus.CANCELLED || latest.isCancelRequested()) return;
        String code = ex instanceof TaskException taskException ? taskException.getCode() : "UNKNOWN_ERROR";
        String message = ex instanceof TaskException ? ex.getMessage() : "工作流执行失败" + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
        try {
            AgentState failed = stateStore.update(taskId, s -> {
                s.setStatus(TaskStatus.FAILED);
                s.setErrorCode(code);
                s.setErrorMessage(message);
            });
            events.publish(taskId, "TASK_FAILED", failed.getCurrentNode(), Map.of("errorCode", code, "message", message));
        } catch (Exception persistException) {
            log.error("固化任务失败状态时再次失败：taskId={}，errorCode={}", taskId, code, persistException);
        }
    }

    /** 把底层缺失状态统一转换为 TASK_NOT_FOUND。 */
    private AgentState load(String taskId) {
        try { return stateStore.load(taskId); }
        catch (IllegalArgumentException ex) { throw new TaskException("TASK_NOT_FOUND", "任务不存在: " + taskId); }
    }

    /** 生成日期加 UUID 短片段的任务标识，便于人工排查且避免并发碰撞。 */
    private String createTaskId() { return "task-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + UUID.randomUUID().toString().substring(0, 8); }

    /** 校验任务操作的生命周期前置条件。 */
    private void require(boolean condition, String code, String message) { if (!condition) throw new TaskException(code, message); }
}
