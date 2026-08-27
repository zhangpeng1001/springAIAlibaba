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
import org.springframework.stereotype.Component;

/**
 * Spring AI Alibaba StateGraph 定义。
 *
 * <p>Graph 负责节点连接、条件边和修复循环；AgentState 则是业务状态唯一真相并独立持久化。
 * 这种分离使服务重启不依赖 Graph 内存 checkpoint，也不会让 LLM 参与状态路由。</p>
 */
@Component
public class AgentWorkflow {
    /**
     * 已编译的流程图。
     *
     * <p>该对象只保存节点和边定义，不保存任何具体任务的业务状态；每次调用 {@link #run}
     * 时会传入 taskId，并由节点自行从 state.json 读取真实状态。</p>
     */
    private final CompiledGraph graph;

    /**
     * 构建并校验完整的 StateGraph。
     *
     * <p>Graph 的内存状态只保存本轮调用所需的路由变量，例如审核是否通过；完整的 Plan、
     * Research、Answer 等大对象只保存在本地状态文件，避免 Graph checkpoint 与业务状态出现双写不一致。</p>
     *
     * @param nodes 各工作流节点的业务实现，由 Spring 注入
     */
    public AgentWorkflow(TaskWorkflowNodes nodes) {
        try {
            // 所有节点都会写入新值，因此路由字段采用 REPLACE，禁止框架尝试把 Boolean 或字符串做集合合并。
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
            /*
             * 通过 CompileConfig 在编译阶段定义递归上限。
             *
             * 不使用已废弃的“编译后再设置迭代上限”方式：审核失败时图会形成
             * "REVIEW → REPAIR → REVIEW" 回边，50 次上限是最后一道框架级保护；
             * 业务层仍以配置的 3 次审核轮数提前失败，防止无意义地耗尽模型调用额度。
             */
            this.graph = stateGraph.compile(CompileConfig.builder().recursionLimit(50).build());
        } catch (Exception ex) {
            throw new IllegalStateException("StateGraph 工作流编译失败", ex);
        }
    }

    /**
     * 执行创建、修订或确认后的图分支。
     *
     * @param taskId 已经创建并落盘的任务标识；不会在该方法中创建新状态文件
     * @param runMode INITIAL 进入分析/初稿，REVISE 处理待处理意见，AUTO 从锁定 Plan 进入自动阶段
     */
    public void run(String taskId, String runMode) {
        graph.invoke(Map.of("taskId", taskId, "runMode", runMode));
    }

    /**
     * 将普通的单任务业务方法包装为 StateGraph 异步节点。
     *
     * <p>包装后节点统一从 Graph state 提取 taskId、调用业务节点，并写回 currentNode，
     * 使图执行轨迹与 state.json 中记录的阶段可以相互核对。</p>
     *
     * @param name 当前图节点名称
     * @param action 接收 taskId 的同步业务动作
     * @return 可注册到 StateGraph 的异步节点动作
     */
    private AsyncNodeAction marker(String name, Consumer<String> action) {
        return AsyncNodeAction.node_async(state -> {
            String taskId = state.value("taskId", String.class).orElseThrow(() -> new IllegalStateException("Graph 缺少 taskId"));
            action.accept(taskId);
            return Map.of("currentNode", name);
        });
    }

    /**
     * 包装审核节点，并把审核布尔结果写入 Graph 的临时路由变量。
     *
     * <p>审核详情仍由业务节点写入 reviews 目录和 AgentState；Graph 只保留 PASS/FAIL
     * 所需的最小信息，避免在图状态中复制大量结构化内容。</p>
     *
     * @param name 当前审核节点名称
     * @param action 返回是否通过的审核业务动作
     * @param resultKey Graph state 中保存审核结果的键
     * @return 可注册到 StateGraph 的审核节点动作
     */
    private AsyncNodeAction reviewNode(String name, java.util.function.Function<String, Boolean> action, String resultKey) {
        return AsyncNodeAction.node_async(state -> {
            String taskId = state.value("taskId", String.class).orElseThrow(() -> new IllegalStateException("Graph 缺少 taskId"));
            return Map.of("currentNode", name, resultKey, action.apply(taskId));
        });
    }

    /**
     * 根据调用方传入的运行模式选择入口节点。
     * 入口分流由 Java 固定枚举控制，绝不依据 LLM 的自然语言输出决定。
     */
    private AsyncEdgeAction routeByMode() {
        return AsyncEdgeAction.edge_async(state -> state.value("runMode", String.class)
                .orElseThrow(() -> new IllegalStateException("Graph 缺少 runMode")));
    }

    /**
     * 将审核节点写入的 Boolean 映射为条件边名称。
     * 键不存在时按失败处理，宁可进入修复也不能错误跳过审核。
     */
    private AsyncEdgeAction booleanRoute(String key) {
        return AsyncEdgeAction.edge_async(state -> state.value(key, Boolean.class).orElse(false) ? "PASS" : "FAIL");
    }
}
