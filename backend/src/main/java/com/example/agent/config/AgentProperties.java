package com.example.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 运行参数。
 * 所有上限集中配置，避免把循环次数、并行度等安全边界散落在业务代码中。
 */
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    /** 本地任务状态和最终 Markdown 输出的根目录配置。 */
    private Storage storage = new Storage();
    /** 输入规模、审核回环和超时等安全上限配置。 */
    private Limits limits = new Limits();
    /** 可并行调用 LLM 的最大线程数配置。 */
    private Executor executor = new Executor();

    public Storage getStorage() { return storage; }
    public void setStorage(Storage storage) { this.storage = storage; }
    public Limits getLimits() { return limits; }
    public void setLimits(Limits limits) { this.limits = limits; }
    public Executor getExecutor() { return executor; }
    public void setExecutor(Executor executor) { this.executor = executor; }

    /**
     * 本地文件存储配置。
     * V1 不使用数据库，因此该路径的可写性直接决定任务创建和恢复是否可用。
     */
    public static class Storage {
        /** 保存 data/tasks/{taskId} 的根目录，包含状态、事件和中间工件。 */
        private String root = "./data";
        /** 保存最终 answer/{title} Markdown 文档的根目录，需与中间状态目录分离。 */
        private String answerRoot = "./answer";
        public String getRoot() { return root; }
        public void setRoot(String root) { this.root = root; }
        public String getAnswerRoot() { return answerRoot; }
        public void setAnswerRoot(String answerRoot) { this.answerRoot = answerRoot; }
    }

    /**
     * 工作流安全上限。
     * 这些值都由 Java 代码强制执行，不应交给 Prompt 或模型自行判断。
     */
    public static class Limits {
        /** Research Review 最多允许进入 REPAIR 回边的次数。 */
        private int maxResearchReviewRounds = 3;
        /** Answer Review 最多允许进入 REPAIR 回边的次数。 */
        private int maxAnswerReviewRounds = 3;
        /** 预留给真实 LLM 适配器的网络重试上限，避免瞬态网络错误立刻失败。 */
        private int maxLlmRetries = 3;
        /** 单任务允许的最长工作时长，适合在外层调度/监控中触发取消。 */
        private int workflowTimeoutMinutes = 60;
        /** 创建任务 API 的问题最大字符数，防止 Prompt 和状态文件无限膨胀。 */
        private int maxQuestionLength = 2000;
        /** Plan 对话单条意见最大字符数，避免无界历史记录。 */
        private int maxFeedbackLength = 4000;
        /** 单个 Plan 最大主题数，限制并行调用规模和最终文件数量。 */
        private int maxPlanItems = 50;
        public int getMaxResearchReviewRounds() { return maxResearchReviewRounds; }
        public void setMaxResearchReviewRounds(int value) { this.maxResearchReviewRounds = value; }
        public int getMaxAnswerReviewRounds() { return maxAnswerReviewRounds; }
        public void setMaxAnswerReviewRounds(int value) { this.maxAnswerReviewRounds = value; }
        public int getMaxLlmRetries() { return maxLlmRetries; }
        public void setMaxLlmRetries(int value) { this.maxLlmRetries = value; }
        public int getWorkflowTimeoutMinutes() { return workflowTimeoutMinutes; }
        public void setWorkflowTimeoutMinutes(int value) { this.workflowTimeoutMinutes = value; }
        public int getMaxQuestionLength() { return maxQuestionLength; }
        public void setMaxQuestionLength(int value) { this.maxQuestionLength = value; }
        public int getMaxFeedbackLength() { return maxFeedbackLength; }
        public void setMaxFeedbackLength(int value) { this.maxFeedbackLength = value; }
        public int getMaxPlanItems() { return maxPlanItems; }
        public void setMaxPlanItems(int value) { this.maxPlanItems = value; }
    }

    /** 并行执行配置。 */
    public static class Executor {
        /** 同时执行主题研究/写作的最大工作线程数；状态写入仍按任务锁串行。 */
        private int parallelism = 4;
        public int getParallelism() { return parallelism; }
        public void setParallelism(int parallelism) { this.parallelism = parallelism; }
    }
}
