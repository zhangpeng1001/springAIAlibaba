package com.example.agent.llm;

import com.example.agent.model.Answer;
import com.example.agent.model.Plan;
import com.example.agent.model.ResearchResult;
import com.example.agent.model.ReviewResult;
import com.example.agent.model.TaskAnalysis;

/**
 * LLM 能力抽象层。
 * 工作流只依赖此接口，不感知具体模型厂商；生产环境可替换为 Spring AI ChatModel 适配器，
 * 测试环境使用确定性的 Fake/Template 实现以保证测试不依赖网络。
 */
public interface LlmService {
    TaskAnalysis analyze(String question);
    Plan draftPlan(String question);
    Plan revisePlan(String question, Plan current, String feedback);
    ResearchResult research(String question, Plan plan, com.example.agent.model.PlanItem item);
    ReviewResult reviewResearch(Plan plan, com.example.agent.model.PlanItem item, ResearchResult result);
    Answer generateAnswer(Plan plan, com.example.agent.model.PlanItem item, ResearchResult research);
    ReviewResult reviewAnswer(com.example.agent.model.PlanItem item, Answer answer);
    String generateTitle(String question, Plan plan);
}
