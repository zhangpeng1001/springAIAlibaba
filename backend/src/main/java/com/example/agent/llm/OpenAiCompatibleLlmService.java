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
    /**
     * Spring AI 统一聊天客户端。
     * 仅在 openai profile 且 API Key 存在时创建，避免默认离线运行也意外发起远程连接。
     */
    private final ChatClient client;

    /**
     * 使用环境配置创建 OpenAI-compatible ChatClient。
     *
     * @param apiKey 模型服务密钥；为空时直接拒绝启动 openai profile，避免运行后才出现不明确的 401
     * @param baseUrl 官方或兼容服务根地址
     * @param model 各角色共用的默认模型名，后续可按角色扩展为多模型路由
     */
    public OpenAiCompatibleLlmService(@Value("${spring.ai.openai.api-key:}") String apiKey,
                                      @Value("${spring.ai.openai.base-url:https://api.openai.com/v1}") String baseUrl,
                                      @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String model) {
        if (apiKey.isBlank()) throw new IllegalStateException("openai profile 需要配置 OPENAI_API_KEY");
        OpenAiApi api = OpenAiApi.builder().baseUrl(normalizeBaseUrl(baseUrl)).apiKey(apiKey).build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(0.2).build()).build();
        this.client = ChatClient.create(chatModel);
    }

    /** 使用任务分析 Prompt，得到结构化分类而不是让模型返回自由文本。 */
    @Override public TaskAnalysis analyze(String question) { return call("task-analyzer/system.txt", "用户问题：\n" + question, TaskAnalysis.class); }

    /** 使用规划 Prompt 创建待确认的 Plan V1。 */
    @Override public Plan draftPlan(String question) { return call("planner/system.txt", "用户问题：\n" + question, Plan.class); }

    /** 把当前 Plan 和本轮反馈一起交给对话规划 Prompt，避免模型丢失现有范围。 */
    @Override public Plan revisePlan(String question, Plan current, String feedback) {
        return call("plan-conversation/system.txt", "用户问题：\n" + question + "\n\n当前 Plan：\n" + json(current) + "\n\n用户意见：\n" + feedback, Plan.class);
    }

    /** 将锁定 Plan 与当前主题一起传入，要求模型只研究该主题。 */
    @Override public ResearchResult research(String question, Plan plan, PlanItem item) {
        return call("researcher/system.txt", "原始问题：\n" + question + "\n\n锁定 Plan：\n" + json(plan) + "\n\n当前主题：\n" + json(item), ResearchResult.class);
    }

    /** 研究审核结果仅用于覆盖、偏题等质量判断，不能修改 Plan 本身。 */
    @Override public ReviewResult reviewResearch(Plan plan, PlanItem item, ResearchResult result) {
        return call("research-reviewer/system.txt", "Plan：\n" + json(plan) + "\n\n当前主题：\n" + json(item) + "\n\n研究结果：\n" + json(result), ReviewResult.class);
    }

    /** 按审核通过的研究结果写作，禁止跳过研究直接凭原问题生成答案。 */
    @Override public Answer generateAnswer(Plan plan, PlanItem item, ResearchResult research) {
        return call("answer/system.txt", "锁定 Plan：\n" + json(plan) + "\n\n当前主题：\n" + json(item) + "\n\n研究结果：\n" + json(research), Answer.class);
    }

    /** 审核单主题答案，返回失败原因供 Answer Repair 节点使用。 */
    @Override public ReviewResult reviewAnswer(PlanItem item, Answer answer) {
        return call("answer-reviewer/system.txt", "当前主题：\n" + json(item) + "\n\n待审核答案：\n" + json(answer), ReviewResult.class);
    }

    /** 标题只是候选文本，文件安全由 FileNameSanitizer 在工作流节点中再次保障。 */
    @Override public String generateTitle(String question, Plan plan) {
        return call("title/system.txt", "用户问题：\n" + question + "\n\n锁定 Plan：\n" + json(plan), String.class).trim();
    }

    /**
     * 将 Prompt 从资源目录读取并要求 Spring AI 直接映射为目标 DTO。
     *
     * <p>不手工截取 Markdown 代码块或解析模型全文。若模型返回不符合 DTO 的内容，框架/调用链会失败，
     * 再由工作流统一记录为 LLM_INVALID_OUTPUT 或任务失败，而不是把不可信文本写入状态文件。</p>
     */
    private <T> T call(String prompt, String user, Class<T> target) {
        return client.prompt().system(readPrompt(prompt)).user(user).call().entity(target);
    }

    /**
     * 加载 classpath 下的 Prompt 文件。
     * Prompt 独立存放使产品规则可审查、可调整，也避免业务代码中堆积长字符串。
     */
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

    /**
     * 将兼容服务根地址标准化到 OpenAI API 的 /v1 根路径。
     * 支持用户配置 https://host、https://host/ 或 https://host/v1，避免重复或遗漏路径段。
     */
    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.replaceAll("/+$", "");
        return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
    }
}
