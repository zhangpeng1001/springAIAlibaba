package com.example.agent.llm;

import com.example.agent.model.Answer;
import com.example.agent.model.Plan;
import com.example.agent.model.PlanItem;
import com.example.agent.model.ResearchResult;
import com.example.agent.model.ReviewResult;
import com.example.agent.model.TaskAnalysis;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * OpenAI-compatible 模型适配器。
 *
 * <p>通过 Spring AI ChatClient 的 entity(Class) 取得结构化 DTO，而非手工截取或解析模型文本。
 * 设置 {@code SPRING_PROFILES_ACTIVE=openai}、{@code OPENAI_API_KEY}、{@code OPENAI_BASE_URL}
 * 和 {@code OPENAI_MODEL} 后启用；未启用时由 TemplateLlmService 提供离线演示能力。</p>
 */
@Service
@Profile("openai")
public class OpenAiCompatibleLlmService implements LlmService {
    private final ChatClient client;

    public OpenAiCompatibleLlmService(@Value("${spring.ai.openai.api-key:}") String apiKey,
                                      @Value("${spring.ai.openai.base-url:https://api.openai.com/v1}") String baseUrl,
                                      @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String model) {
        if (apiKey.isBlank()) throw new IllegalStateException("openai profile 需要配置 OPENAI_API_KEY");
        OpenAiApi api = OpenAiApi.builder().baseUrl(normalizeBaseUrl(baseUrl)).apiKey(apiKey).build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(0.2).build()).build();
        this.client = ChatClient.create(chatModel);
    }

    @Override public TaskAnalysis analyze(String question) { return call("task-analyzer/system.txt", "用户问题：\n" + question, TaskAnalysis.class); }
    @Override public Plan draftPlan(String question) { return call("planner/system.txt", "用户问题：\n" + question, Plan.class); }

    @Override public Plan revisePlan(String question, Plan current, String feedback) {
        return call("plan-conversation/system.txt", "用户问题：\n" + question + "\n\n当前 Plan：\n" + json(current) + "\n\n用户意见：\n" + feedback, Plan.class);
    }

    @Override public ResearchResult research(String question, Plan plan, PlanItem item) {
        return call("researcher/system.txt", "原始问题：\n" + question + "\n\n锁定 Plan：\n" + json(plan) + "\n\n当前主题：\n" + json(item), ResearchResult.class);
    }

    @Override public ReviewResult reviewResearch(Plan plan, PlanItem item, ResearchResult result) {
        return call("research-reviewer/system.txt", "Plan：\n" + json(plan) + "\n\n当前主题：\n" + json(item) + "\n\n研究结果：\n" + json(result), ReviewResult.class);
    }

    @Override public Answer generateAnswer(Plan plan, PlanItem item, ResearchResult research) {
        return call("answer/system.txt", "锁定 Plan：\n" + json(plan) + "\n\n当前主题：\n" + json(item) + "\n\n研究结果：\n" + json(research), Answer.class);
    }

    @Override public ReviewResult reviewAnswer(PlanItem item, Answer answer) {
        return call("answer-reviewer/system.txt", "当前主题：\n" + json(item) + "\n\n待审核答案：\n" + json(answer), ReviewResult.class);
    }

    @Override public String generateTitle(String question, Plan plan) {
        return call("title/system.txt", "用户问题：\n" + question + "\n\n锁定 Plan：\n" + json(plan), String.class).trim();
    }

    /** 将 Prompt 从资源目录读取，保证业务规则可独立于 Java 源码演进。 */
    private <T> T call(String prompt, String user, Class<T> target) {
        return client.prompt().system(readPrompt(prompt)).user(user).call().entity(target);
    }

    private String readPrompt(String name) {
        try (var stream = new ClassPathResource("prompts/" + name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) { throw new IllegalStateException("读取 Prompt 失败: " + name, ex); }
    }

    /** JSON 仅作为 Prompt 上下文，结构化响应仍由 Spring AI 直接映射。 */
    private String json(Object value) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("构建模型上下文失败", ex); }
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.replaceAll("/+$", "");
        return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
    }
}
