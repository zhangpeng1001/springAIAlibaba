package com.example.agent.llm;

import com.example.agent.exception.TaskException;
import com.example.agent.model.Answer;
import com.example.agent.model.Plan;
import com.example.agent.model.PlanItem;
import com.example.agent.model.ResearchResult;
import com.example.agent.model.ReviewResult;
import com.example.agent.model.TaskAnalysis;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.CompositeResponseTextCleaner;
import org.springframework.ai.converter.MarkdownCodeBlockCleaner;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.ai.converter.ThinkingTagCleaner;
import org.springframework.ai.converter.WhitespaceCleaner;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI-compatible 模型适配器。
 *
 * <p>通过 Spring AI ChatClient 的结构化输出转换器取得 DTO，而非把模型全文当作业务文本处理。
 * 设置 {@code SPRING_PROFILES_ACTIVE=openai}、{@code OPENAI_API_KEY}、{@code OPENAI_BASE_URL}
 * 和 {@code OPENAI_MODEL} 后启用；未启用时由 TemplateLlmService 提供离线演示能力。</p>
 */
@Service
@Profile("openai")
public class OpenAiCompatibleLlmService implements LlmService {
    /**
     * 模型调用诊断日志。
     *
     * <p>只记录组件名、目标类型、耗时、根地址和异常栈，不记录 API Key、完整 Prompt 或用户问题，
     * 在满足排障需要的同时避免把敏感内容写入日志文件。</p>
     */
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmService.class);

    /**
     * Spring AI 统一聊天客户端。
     * 仅在 openai profile 且 API Key 存在时创建，避免默认离线运行也意外发起远程连接。
     */
    private final ChatClient client;

    /**
     * 供 BeanOutputConverter 使用的 Jackson 映射器。
     *
     * <p>Spring AI 默认映射器会把 record 的集合访问器识别成 setterless 属性；部分兼容模型
     * 又会重复输出同名 JSON 字段，二者叠加后会触发 {@code Should never call set()} 或
     * {@code No fallback setter/field}。关闭 getter 作为 setter 的推断后，record 只通过规范构造器
     * 绑定；重复字段则由 {@link #normalizeJson(String)} 在树规范化阶段按最后值折叠，同时仍由业务层校验关键标识。</p>
     */
    private static final ObjectMapper OUTPUT_MAPPER = JsonMapper.builder()
            .configure(MapperFeature.USE_GETTERS_AS_SETTERS, false)
            // 与 Spring AI 默认转换器保持一致：模型偶尔附带说明字段时，不因未知字段丢弃而整体失败。
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    /** 复用 Spring AI 的标准响应清理顺序，先去除思考标签/代码围栏再进行 JSON 树规范化。 */
    private static final CompositeResponseTextCleaner RESPONSE_TEXT_CLEANER = new CompositeResponseTextCleaner(
            new WhitespaceCleaner(), new ThinkingTagCleaner(), new MarkdownCodeBlockCleaner(), new WhitespaceCleaner());

    /** 默认网络超时，既保留旧构造器兼容性，也让配置文件的默认值与代码保持一致。 */
    private static final long DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    private static final long DEFAULT_READ_TIMEOUT_SECONDS = 300;

    /**
     * 保留三参数构造器，供离线单元测试或已有集成代码直接创建适配器。
     * Spring 注入使用下方带完整超时参数的构造器，因此不会绕过运行时配置。
     */
    public OpenAiCompatibleLlmService(String apiKey, String baseUrl, String model) {
        this(apiKey, baseUrl, model, DEFAULT_CONNECT_TIMEOUT_SECONDS, DEFAULT_READ_TIMEOUT_SECONDS);
    }

    /**
     * 使用环境配置创建 OpenAI-compatible ChatClient。
     *
     * @param apiKey 模型服务密钥；为空时直接拒绝启动 openai profile，避免运行后才出现不明确的 401
     * @param baseUrl 官方或兼容服务根地址
     * @param model 各角色共用的默认模型名，后续可按角色扩展为多模型路由
     * @param connectTimeoutSeconds TCP 建连超时秒数；过小会把瞬时网络抖动误判为模型失败
     * @param readTimeoutSeconds 响应读取超时秒数；必须覆盖深度模型较慢的首 token 和完整响应时间
     */
    @Autowired
    public OpenAiCompatibleLlmService(@Value("${spring.ai.openai.api-key:}") String apiKey,
                                      @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
                                      @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String model,
                                      @Value("${agent.llm.connect-timeout-seconds:10}") long connectTimeoutSeconds,
                                      @Value("${agent.llm.read-timeout-seconds:300}") long readTimeoutSeconds) {
        if (apiKey.isBlank()) throw new IllegalStateException("openai profile 需要配置 OPENAI_API_KEY");
        if (connectTimeoutSeconds <= 0 || readTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("模型连接和读取超时必须是正数秒");
        }
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        Duration connectTimeout = Duration.ofSeconds(connectTimeoutSeconds);
        Duration readTimeout = Duration.ofSeconds(readTimeoutSeconds);
        // 同一 JDK HttpClient 同时提供给同步 RestClient 和流式 WebClient，避免两条调用路径超时口径不一致。
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        JdkClientHttpConnector webConnector = new JdkClientHttpConnector(httpClient);
        webConnector.setReadTimeout(readTimeout);
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(normalizedBaseUrl)
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder().clientConnector(webConnector))
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(0.2).build()).build();
        this.client = ChatClient.create(chatModel);
        // 当前 Spring AI 版本会基于该根地址追加 /v1/chat/completions，启动时明确打印最终约定，
        // 可在未创建任务前立即发现环境变量把 /v1 重复配置的问题。
        log.info("已初始化 OpenAI-compatible 模型客户端：baseUrl={}，请求路径由 Spring AI 追加为 /v1/chat/completions，model={}，connectTimeoutSeconds={}，readTimeoutSeconds={}",
                normalizedBaseUrl, model, connectTimeoutSeconds, readTimeoutSeconds);
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
     * <p>不手工解析 DTO 字段；仅在转换器入口统一 JSON 结构，避免兼容模型重复键触发框架属性冲突。
     * 若模型返回不符合 DTO 的内容，框架/调用链会失败，
     * 再由工作流统一记录为 LLM_INVALID_OUTPUT 或任务失败，而不是把不可信文本写入状态文件。</p>
     */
    private <T> T call(String prompt, String user, Class<T> target) {
        long startedAt = System.nanoTime();
        log.info("开始调用模型：prompt={}，targetType={}，userContentLength={}", prompt, target.getSimpleName(), user.length());
        try {
            // 显式传入兼容 record 的转换器，避免 Spring AI 默认 BeanOutputConverter 对集合 record
            // 生成 setterless 属性；模型即便重复返回字段，也不会再因框架属性回写阶段失败。
            T result = client.prompt().system(readPrompt(prompt)).user(user).call()
                    .entity(outputConverter(target));
            log.info("模型调用成功：prompt={}，targetType={}，durationMs={}", prompt, target.getSimpleName(), elapsedMillis(startedAt));
            return result;
        } catch (Exception ex) {
            // 异常栈必须在服务端完整记录。前端只收到稳定错误码和可操作提示，避免远程响应、路径或
            // 框架实现细节被直接暴露；运维可凭 taskId、节点日志和此异常栈定位根因。
            log.error("模型调用失败：prompt={}，targetType={}，durationMs={}，exceptionType={}，message={}",
                    prompt, target.getSimpleName(), elapsedMillis(startedAt), ex.getClass().getName(), ex.getMessage(), ex);
            throw new TaskException("LLM_REQUEST_FAILED", "模型服务调用失败，请检查服务地址、模型名称和访问密钥；详细原因请查看后端日志", true);
        }
    }

    /**
     * 创建可兼容重复 JSON 字段的结构化输出转换器。
     *
     * <p>先把模型文本读成 JSON 树再重新序列化，会按 Jackson 的对象节点语义合并重复字段，
     * 然后交给 Spring AI 原有转换器完成类型绑定和响应清理。若模型返回的不是合法 JSON，
     * 保留原文交给委托转换器，使其继续产生标准的解析异常而不吞掉真正的输出问题。</p>
     */
    private <T> StructuredOutputConverter<T> outputConverter(Class<T> target) {
        BeanOutputConverter<T> delegate = new BeanOutputConverter<>(target, OUTPUT_MAPPER);
        return new StructuredOutputConverter<>() {
            @Override
            public T convert(String text) {
                String normalized;
                try {
                    normalized = normalizeJson(text);
                } catch (RuntimeException normalizationFailure) {
                    // normalizeJson 失败时让原转换器保留原始清理和异常语义，避免掩盖模型实际输出。
                    return delegate.convert(text);
                }
                return delegate.convert(normalized);
            }

            @Override
            public String getFormat() { return delegate.getFormat(); }
        };
    }

    /**
     * 规范化模型 JSON 文本并折叠重复字段。
     *
     * @param text 模型返回的 JSON，允许带常见的 markdown 代码围栏
     * @return 无代码围栏且对象字段唯一的 JSON 文本
     */
    static String normalizeJson(String text) {
        String cleaned = RESPONSE_TEXT_CLEANER.clean(text == null ? "" : text);
        try {
            return OUTPUT_MAPPER.writeValueAsString(OUTPUT_MAPPER.readTree(cleaned));
        } catch (Exception ex) {
            throw new IllegalArgumentException("模型返回内容不是合法 JSON", ex);
        }
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
     * 将用户输入标准化为 Spring AI 所需的服务根地址（不含 {@code /v1}）。
     *
     * <p>当前版本 {@link OpenAiApi} 会自行把 {@code /v1/chat/completions} 追加到 baseUrl。
     * 因此保留 {@code /v1} 会造成实际请求为 {@code /v1/v1/chat/completions}，进而被兼容服务以 404
     * 拒绝。这里兼容 {@code https://host}、{@code https://host/}、{@code https://host/v1} 以及多次
     * 误拼接的末尾 {@code /v1}，将配置错误在客户端构建阶段彻底消除。</p>
     *
     * @param baseUrl 环境变量或配置文件提供的服务地址
     * @return 不以斜杠或 {@code /v1} 结尾的服务根地址
     * @throws IllegalArgumentException 地址为空或仅包含 API 路径时抛出，避免在工作流中才发生不明 404
     */
    static String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        // (?i) 兼容 /V1；重复剥离可修复已经被手工拼成 /v1/v1 的历史环境变量。
        normalized = normalized.replaceFirst("(?i)(/v1)+$", "");
        if (normalized.isBlank() || !normalized.matches("https?://.+")) {
            throw new IllegalArgumentException("OPENAI_BASE_URL 必须是以 http:// 或 https:// 开头的服务根地址");
        }
        return normalized;
    }

    /** 将纳秒起点转换为毫秒，统一模型调用成功与失败日志中的耗时统计口径。 */
    private long elapsedMillis(long startedAt) { return Duration.ofNanos(System.nanoTime() - startedAt).toMillis(); }
}
