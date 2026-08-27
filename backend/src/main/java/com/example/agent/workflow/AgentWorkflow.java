package com.example.agent.workflow;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.example.agent.service.TaskWorkflowNodes;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Spring AI Alibaba StateGraph 定义。
 *
 * <p>Graph 负责节点连接、条件边和修复循环；AgentState 则是业务状态唯一真相并独立持久化。
 * 这种分离使服务重启不依赖 Graph 内存 checkpoint，也不会让 LLM 参与状态路由。</p>
 */
@Component
public class AgentWorkflow {
    private final CompiledGraph graph;

    public AgentWorkflow(TaskWorkflowNodes nodes) {
        try {
            StateGraph stateGraph = new StateGraph(() -> Map.of(
                    "taskId", KeyStrategy.REPLACE,
                    "runMode", KeyStrategy.REPLACE,
                    "currentNode", KeyStrategy.REPLACE,
                    "researchPassed", KeyStrategy.REPLACE,
                    "answerPassed", KeyStrategy.REPLACE));

            stateGraph.addNode("ROUTER", marker("ROUTER", ignored -> { }))
                    .addNode("TASK_ANALYZE", marker("TASK_ANALYZE", nodes::taskAnalyze))
                    .addNode("PLAN_DRAFT", marker("PLAN_DRAFT", nodes::planDraft))
                    .addNode("WAITING_USER_PLAN", marker("WAITING_USER_PLAN", ignored -> { }))
                    .addNode("PLAN_REVISE", marker("PLAN_REVISE", nodes::planRevise))
                    .addNode("PLAN_LOCK", marker("PLAN_LOCK", nodes::planLock))
                    .addNode("RESEARCH", marker("RESEARCH", nodes::research))
                    .addNode("RESEARCH_REVIEW", reviewNode("RESEARCH_REVIEW", nodes::researchReview, "researchPassed"))
                    .addNode("RESEARCH_REPAIR", marker("RESEARCH_REPAIR", nodes::researchRepair))
                    .addNode("ANSWER_GENERATE", marker("ANSWER_GENERATE", nodes::answerGenerate))
                    .addNode("ANSWER_REVIEW", reviewNode("ANSWER_REVIEW", nodes::answerReview, "answerPassed"))
                    .addNode("ANSWER_REPAIR", marker("ANSWER_REPAIR", nodes::answerRepair))
                    .addNode("TITLE_GENERATE", marker("TITLE_GENERATE", nodes::titleGenerate))
                    .addNode("FILE_GENERATE", marker("FILE_GENERATE", nodes::fileGenerate))
                    .addNode("RESULT_COLLECT", marker("RESULT_COLLECT", nodes::resultCollect))
                    .addEdge(StateGraph.START, "ROUTER")
                    .addConditionalEdges("ROUTER", routeByMode(), Map.of(
                            "INITIAL", "TASK_ANALYZE", "REVISE", "PLAN_REVISE", "AUTO", "PLAN_LOCK"))
                    .addEdge("TASK_ANALYZE", "PLAN_DRAFT")
                    .addEdge("PLAN_DRAFT", "WAITING_USER_PLAN")
                    .addEdge("PLAN_REVISE", "WAITING_USER_PLAN")
                    .addEdge("WAITING_USER_PLAN", StateGraph.END)
                    .addEdge("PLAN_LOCK", "RESEARCH")
                    .addEdge("RESEARCH", "RESEARCH_REVIEW")
                    .addConditionalEdges("RESEARCH_REVIEW", booleanRoute("researchPassed"), Map.of(
                            "PASS", "ANSWER_GENERATE", "FAIL", "RESEARCH_REPAIR"))
                    .addEdge("RESEARCH_REPAIR", "RESEARCH_REVIEW")
                    .addEdge("ANSWER_GENERATE", "ANSWER_REVIEW")
                    .addConditionalEdges("ANSWER_REVIEW", booleanRoute("answerPassed"), Map.of(
                            "PASS", "TITLE_GENERATE", "FAIL", "ANSWER_REPAIR"))
                    .addEdge("ANSWER_REPAIR", "ANSWER_REVIEW")
                    .addEdge("TITLE_GENERATE", "FILE_GENERATE")
                    .addEdge("FILE_GENERATE", "RESULT_COLLECT")
                    .addEdge("RESULT_COLLECT", StateGraph.END);
            this.graph = stateGraph.compile();
            this.graph.setMaxIterations(50);
        } catch (Exception ex) {
            throw new IllegalStateException("StateGraph 工作流编译失败", ex);
        }
    }

    /** 执行创建、修订或确认后的完整图；任务标识仅存在于本次 Graph 调用的状态中。 */
    public void run(String taskId, String runMode) {
        graph.invoke(Map.of("taskId", taskId, "runMode", runMode));
    }

    private AsyncNodeAction marker(String name, Consumer<String> action) {
        return AsyncNodeAction.node_async(state -> {
            String taskId = state.value("taskId", String.class).orElseThrow(() -> new IllegalStateException("Graph 缺少 taskId"));
            action.accept(taskId);
            return Map.of("currentNode", name);
        });
    }

    private AsyncNodeAction reviewNode(String name, java.util.function.Function<String, Boolean> action, String resultKey) {
        return AsyncNodeAction.node_async(state -> {
            String taskId = state.value("taskId", String.class).orElseThrow(() -> new IllegalStateException("Graph 缺少 taskId"));
            return Map.of("currentNode", name, resultKey, action.apply(taskId));
        });
    }

    private AsyncEdgeAction routeByMode() {
        return AsyncEdgeAction.edge_async(state -> state.value("runMode", String.class)
                .orElseThrow(() -> new IllegalStateException("Graph 缺少 runMode")));
    }

    private AsyncEdgeAction booleanRoute(String key) {
        return AsyncEdgeAction.edge_async(state -> state.value(key, Boolean.class).orElse(false) ? "PASS" : "FAIL");
    }
}
