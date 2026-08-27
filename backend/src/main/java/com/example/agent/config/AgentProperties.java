package com.example.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 运行参数。
 * 所有上限集中配置，避免把循环次数、并行度等安全边界散落在业务代码中。
 */
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    private Storage storage = new Storage();
    private Limits limits = new Limits();
    private Executor executor = new Executor();

    public Storage getStorage() { return storage; }
    public void setStorage(Storage storage) { this.storage = storage; }
    public Limits getLimits() { return limits; }
    public void setLimits(Limits limits) { this.limits = limits; }
    public Executor getExecutor() { return executor; }
    public void setExecutor(Executor executor) { this.executor = executor; }

    public static class Storage {
        private String root = "./data";
        private String answerRoot = "./answer";
        public String getRoot() { return root; }
        public void setRoot(String root) { this.root = root; }
        public String getAnswerRoot() { return answerRoot; }
        public void setAnswerRoot(String answerRoot) { this.answerRoot = answerRoot; }
    }

    public static class Limits {
        private int maxResearchReviewRounds = 3;
        private int maxAnswerReviewRounds = 3;
        private int maxLlmRetries = 3;
        private int workflowTimeoutMinutes = 60;
        private int maxQuestionLength = 2000;
        private int maxFeedbackLength = 4000;
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

    public static class Executor {
        private int parallelism = 4;
        public int getParallelism() { return parallelism; }
        public void setParallelism(int parallelism) { this.parallelism = parallelism; }
    }
}
