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
 * 面向 Controller 的任务门面。
 * 该类只处理 API 状态校验、异步提交和失败兜底；具体语言任务全部委托给 StateGraph 节点。
 */
@Service
public class TaskService {
    /**
     * 任务异步调度日志。
     *
     * <p>异步线程不继承 HTTP 请求 MDC，因此此处始终显式打印 taskId、运行模式和当前节点，
     * 确保从浏览器受理到后台 Graph 执行、失败落盘的整条链路均可独立检索。</p>
     */
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /**
     * 唯一业务状态仓库。
     * 所有 API 状态校验都必须基于刚从磁盘读取的状态，不能依赖 Controller 或前端缓存。
     */
    private final TaskStateStore stateStore;
    /** 负责追加审计日志并向已连接浏览器分发 SSE 事件。 */
    private final TaskEventService events;
    /** 已编译的 StateGraph，负责把 INITIAL、REVISE、AUTO 三种调用模式连接到正确节点。 */
    private final AgentWorkflow workflow;
    /**
     * 后台执行器。
     * HTTP 接口只提交任务并返回 202，避免浏览器连接被长时间的 LLM 调用占用。
     */
    private final ExecutorService executor;

    /**
     * 注入任务门面依赖。
     *
     * @param stateStore 文件系统状态仓库
     * @param events SSE 与 JSONL 事件服务
     * @param workflow 任务流程图
     * @param agentExecutor 受限并发后台执行器
     */
    public TaskService(TaskStateStore stateStore, TaskEventService events, AgentWorkflow workflow,
                       ExecutorService agentExecutor) {
        this.stateStore = stateStore;
        this.events = events;
        this.workflow = workflow;
        this.executor = agentExecutor;
    }

    /**
     * 创建任务并异步运行“分析 → 初始纲要 → 等待人工”的图分支。
     *
     * <p>必须先落盘再发送 TASK_CREATED 事件，否则浏览器在收到事件后立刻请求详情可能找不到状态文件。</p>
     *
     * @param question 已经过 Controller 长度校验的用户问题
     * @return 已持久化的初始任务状态；其后续状态通过 SSE 或查询接口获取
     */
    public AgentState create(String question) {
        String taskId = createTaskId();
        AgentState state = AgentState.created(taskId, question.trim());
        stateStore.create(state);
        log.info("已创建任务状态：taskId={}，questionLength={}，status={}", taskId, state.getQuestion().length(), state.getStatus());
        events.publish(taskId, "TASK_CREATED", "START", Map.of("question", state.getQuestion()));
        submit(taskId, "INITIAL");
        return state;
    }

    /**
     * 读取某个任务的完整可恢复状态。
     *
     * @param taskId 任务标识
     * @return 当前磁盘快照，不返回内存缓存
     */
    public AgentState get(String taskId) { return load(taskId); }

    /**
     * 返回最近更新时间倒序的任务列表，供首页历史任务区域展示。
     * 每次均扫描 state.json，保证服务重启后历史任务仍然可见。
     */
    public List<AgentState> list() {
        return stateStore.list().stream().sorted(Comparator.comparing(AgentState::getUpdatedAt).reversed()).toList();
    }

    /**
     * 保存待处理意见并触发 REVISE 图分支。
     * 自然语言中即使出现“确认”也不会锁定 Plan，确认只能走 confirmPlan 接口。
     *
     * @param taskId 当前处于 Human Gate 的任务标识
     * @param message 用户对现有纲要的修改意见
     */
    public void revisePlan(String taskId, String message) {
        AgentState state = stateStore.update(taskId, s -> {
            require(s.getStatus() == TaskStatus.WAITING_USER_PLAN && !s.isPlanLocked() && !s.isPlanConfirmed(), "PLAN_REVISION_FAILED", "当前任务不处于可修改纲要状态");
            s.setPendingPlanFeedback(message.trim());
            s.setStatus(TaskStatus.PLAN_REVISING);
        });
        events.publish(taskId, "PLAN_REVISION_RECEIVED", "PLAN_REVISE", Map.of("message", message.trim(), "planVersion", state.getPlanVersion()));
        log.info("已保存纲要修改意见并准备调度：taskId={}，planVersion={}，messageLength={}", taskId,
                state.getPlanVersion(), message.trim().length());
        submit(taskId, "REVISE");
    }

    /**
     * 校验客户端看到的 Plan 版本后记录显式确认，再触发自动阶段。
     * 不在本方法直接调用 LLM，保证 HTTP 请求快速返回 202。
     *
     * <p>版本检查用于防止用户在浏览器展示 V1 时，误把另一标签页刚更新出的 V2 锁定。</p>
     *
     * @param taskId 待确认任务标识
     * @param planVersion 用户界面上显示并由用户确认的纲要版本
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
        log.info("已记录纲要确认并准备进入自动流程：taskId={}，planVersion={}", taskId, planVersion);
        submit(taskId, "AUTO");
    }

    /**
     * 协作式取消任务。
     *
     * <p>不能强行中断正在运行的远程 HTTP 请求，因此这里只落盘取消标记；工作流节点会在开始、
     * 模型返回与并行汇总点检查该标记，从而阻止任务继续写入后续阶段的结果。</p>
     *
     * @param taskId 待取消任务标识
     * @return 写入 CANCELLED 后的任务状态
     */
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
        log.info("已将任务标记为取消：taskId={}，previousOrCurrentNode={}", taskId, state.getCurrentNode());
        return state;
    }

    /**
     * 服务启动恢复。
     *
     * <p>等待人工确认的任务绝不自动继续；修订中的任务依赖 pendingPlanFeedback 重新进入 REVISE；
     * 已确认或已锁定任务从 AUTO 入口恢复。各节点会跳过已经落盘的主题工件。</p>
     */
    public void recoverIncompleteTasks() {
        List<AgentState> candidates = stateStore.list();
        log.info("开始扫描待恢复任务：candidateCount={}", candidates.size());
        for (AgentState state : candidates) {
            if (state.getStatus() == TaskStatus.WAITING_USER_PLAN || state.getStatus() == TaskStatus.SUCCESS
                    || state.getStatus() == TaskStatus.FAILED || state.getStatus() == TaskStatus.CANCELLED) {
                log.info("恢复扫描跳过终态或人工等待任务：taskId={}，status={}", state.getTaskId(), state.getStatus());
                continue;
            }
            String mode = state.getStatus() == TaskStatus.PLAN_REVISING ? "REVISE"
                    : state.isPlanConfirmed() || state.isPlanLocked() ? "AUTO" : "INITIAL";
            events.publish(state.getTaskId(), "TASK_RECOVERY_STARTED", "RECOVERY", Map.of("mode", mode));
            log.info("已识别可恢复任务：taskId={}，status={}，currentNode={}，resumeMode={}", state.getTaskId(),
                    state.getStatus(), state.getCurrentNode(), mode);
            submit(state.getTaskId(), mode);
        }
    }

    /**
     * 把 Graph 调用提交到后台，并把任何未被业务节点消化的异常统一转换为 FAILED 状态。
     *
     * @param taskId 任务标识
     * @param mode Graph 入口模式
     */
    private void submit(String taskId, String mode) {
        log.info("已提交后台工作流：taskId={}，mode={}", taskId, mode);
        executor.submit(() -> {
            long startedAt = System.nanoTime();
            try {
                log.info("后台工作流开始执行：taskId={}，mode={}", taskId, mode);
                workflow.run(taskId, mode);
                AgentState completed = stateStore.load(taskId);
                log.info("后台工作流执行结束：taskId={}，mode={}，status={}，currentNode={}，durationMs={}", taskId,
                        mode, completed.getStatus(), completed.getCurrentNode(), elapsedMillis(startedAt));
            } catch (Exception ex) {
                // 失败前先打出完整异常栈；fail 内的状态写入若二次失败，仍可依赖这一条日志定位原始问题。
                log.error("后台工作流执行异常：taskId={}，mode={}，durationMs={}，exceptionType={}，message={}", taskId,
                        mode, elapsedMillis(startedAt), ex.getClass().getName(), ex.getMessage(), ex);
                fail(taskId, ex);
            }
        });
    }

    /**
     * 将后台异常固化为可观察失败状态，并发送终态事件。
     * 已取消任务不应被较晚返回的 LLM 异常覆盖为 FAILED，因此先检查取消标记。
     */
    private void fail(String taskId, Exception ex) {
        AgentState latest;
        try {
            latest = stateStore.load(taskId);
        } catch (Exception loadException) {
            log.error("工作流异常后无法读取任务状态，无法固化失败结果：taskId={}", taskId, loadException);
            return;
        }
        if (latest.getStatus() == TaskStatus.CANCELLED || latest.isCancelRequested()) {
            log.info("任务已取消，忽略随后返回的工作流异常：taskId={}，exceptionType={}", taskId, ex.getClass().getName());
            return;
        }
        String code = ex instanceof TaskException taskException ? taskException.getCode() : "UNKNOWN_ERROR";
        // 保留框架异常的摘要，便于本地状态文件和前端错误面板定位 Graph/IO 故障；
        // 真实模型响应正文不在这里写入，避免把潜在敏感提示词落盘。
        String message = ex instanceof TaskException ? ex.getMessage()
                : "工作流执行失败" + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
        try {
            AgentState failed = stateStore.update(taskId, s -> {
                s.setStatus(TaskStatus.FAILED);
                s.setErrorCode(code);
                s.setErrorMessage(message);
            });
            events.publish(taskId, "TASK_FAILED", failed.getCurrentNode(), Map.of("errorCode", code, "message", message));
            log.error("任务已固化为失败状态：taskId={}，stage={}，errorCode={}，retryable={}，message={}", taskId,
                    failed.getCurrentNode(), code, ex instanceof TaskException taskException && taskException.isRetryable(), message);
        } catch (Exception persistException) {
            log.error("工作流异常后固化失败状态或发布失败事件时再次失败：taskId={}，originalErrorCode={}", taskId, code,
                    persistException);
        }
    }

    /** 将底层状态文件不存在/损坏异常转为统一的 TASK_NOT_FOUND API 语义。 */
    private AgentState load(String taskId) {
        try { return stateStore.load(taskId); }
        catch (IllegalArgumentException ex) {
            log.warn("读取任务失败：taskId={}，message={}", taskId, ex.getMessage());
            throw new TaskException("TASK_NOT_FOUND", "任务不存在: " + taskId);
        }
    }

    /**
     * 生成同时可读、可排序且低碰撞的任务目录名。
     * 日期便于人工排查，UUID 短片段避免同一天并发创建任务冲突。
     */
    private String createTaskId() {
        return "task-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 统一业务前置条件校验。
     * 条件失败时必须携带稳定错误码，前端据此展示正确操作提示，而不是解析异常文本。
     */
    private void require(boolean condition, String code, String message) { if (!condition) throw new TaskException(code, message); }

    /** 将工作流或节点开始时间转换为毫秒，保持后台异步日志的耗时口径统一。 */
    private long elapsedMillis(long startedAt) { return java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis(); }
}
