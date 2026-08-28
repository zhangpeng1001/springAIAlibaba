package com.example.agent.workflow;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.example.agent.service.TaskWorkflowNodes;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 线性 StateGraph 定义。
 *
 * <p>Graph 只负责固定节点顺序；具体任务状态仍由节点从 state.json 读取和持久化，
 * 因此服务重启时可以依据快照选择尚未完成的阶段，而不会重新进入人工确认或审核回环。</p>
 */
@Component
public class AgentWorkflow {
    private static final Logger log = LoggerFactory.getLogger(AgentWorkflow.class);
    private final CompiledGraph graph;

    /** 构建六阶段线性图，并保留有限递归上限作为框架级保护。 */
    public AgentWorkflow(TaskWorkflowNodes nodes) {
        try {
            StateGraph stateGraph = new StateGraph(() -> Map.of(
                    "taskId", KeyStrategy.REPLACE,
                    "entry", KeyStrategy.REPLACE,
                    "currentNode", KeyStrategy.REPLACE));
            stateGraph.addNode("ROUTER", marker("ROUTER", ignored -> { }))
                    .addNode("TASK_ANALYZE", marker("TASK_ANALYZE", nodes::taskAnalyze))
                    .addNode("PLAN_DRAFT", marker("PLAN_DRAFT", nodes::planDraft))
                    .addNode("ANSWER_GENERATE", marker("ANSWER_GENERATE", nodes::answerGenerate))
                    .addNode("TITLE_GENERATE", marker("TITLE_GENERATE", nodes::titleGenerate))
                    .addNode("FILE_GENERATE", marker("FILE_GENERATE", nodes::fileGenerate))
                    .addNode("RESULT_COLLECT", marker("RESULT_COLLECT", nodes::resultCollect))
                    .addEdge(StateGraph.START, "ROUTER")
                    .addConditionalEdges("ROUTER", routeEntry(), Map.of(
                            "TASK_ANALYZE", "TASK_ANALYZE",
                            "PLAN_DRAFT", "PLAN_DRAFT",
                            "ANSWER_GENERATE", "ANSWER_GENERATE",
                            "TITLE_GENERATE", "TITLE_GENERATE",
                            "FILE_GENERATE", "FILE_GENERATE",
                            "RESULT_COLLECT", "RESULT_COLLECT"))
                    .addEdge("TASK_ANALYZE", "PLAN_DRAFT")
                    .addEdge("PLAN_DRAFT", "ANSWER_GENERATE")
                    .addEdge("ANSWER_GENERATE", "TITLE_GENERATE")
                    .addEdge("TITLE_GENERATE", "FILE_GENERATE")
                    .addEdge("FILE_GENERATE", "RESULT_COLLECT")
                    .addEdge("RESULT_COLLECT", StateGraph.END);
            this.graph = stateGraph.compile(CompileConfig.builder().recursionLimit(20).build());
        } catch (Exception ex) {
            throw new IllegalStateException("StateGraph 线性工作流编译失败", ex);
        }
    }

    /** 执行新任务或恢复任务；entry 由 TaskService 按持久化状态决定。 */
    public void run(String taskId, String entry) {
        log.info("开始调用线性 StateGraph：taskId={}，entry={}", taskId, entry);
        graph.invoke(Map.of("taskId", taskId, "entry", entry));
        log.info("线性 StateGraph 调用返回：taskId={}，entry={}", taskId, entry);
    }

    /** 将节点业务动作统一包装为带 taskId 日志和 currentNode 回写的异步节点。 */
    private AsyncNodeAction marker(String name, Consumer<String> action) {
        return AsyncNodeAction.node_async(state -> {
            String taskId = state.value("taskId", String.class).orElseThrow(() -> new IllegalStateException("Graph 缺少 taskId"));
            long startedAt = System.nanoTime();
            try {
                log.info("线性节点开始：taskId={}，node={}", taskId, name);
                action.accept(taskId);
                log.info("线性节点完成：taskId={}，node={}，durationMs={}", taskId, name, elapsedMillis(startedAt));
                return Map.of("currentNode", name);
            } catch (RuntimeException ex) {
                log.error("线性节点失败：taskId={}，node={}，durationMs={}，message={}", taskId, name,
                        elapsedMillis(startedAt), ex.getMessage(), ex);
                throw ex;
            }
        });
    }

    /** 将 TaskService 计算出的入口节点转换为 Graph 条件边名称。 */
    private AsyncEdgeAction routeEntry() {
        return AsyncEdgeAction.edge_async(state -> state.value("entry", String.class)
                .orElseThrow(() -> new IllegalStateException("Graph 缺少 entry")));
    }

    /** 统一计算节点耗时，保证诊断日志使用毫秒口径。 */
    private long elapsedMillis(long startedAt) { return java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis(); }
}
