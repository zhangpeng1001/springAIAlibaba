package com.example.agent.llm;

import com.example.agent.model.ResearchResult;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 OpenAI-compatible 根地址规范化规则。
 *
 * <p>本测试不创建真实 HTTP 客户端，也不访问远程模型；它专门防止升级 Spring AI 或重构配置代码后，
 * 重新把 {@code /v1} 同时写进 baseUrl 和客户端固定路径，导致请求退化为 404。</p>
 */
class OpenAiCompatibleLlmServiceTest {

    /**
     * 回归验证兼容模型偶发重复字段时，record 集合属性仍能被稳定转换。
     *
     * <p>线上模型曾对同一 detail 重复输出 title/questions；Spring AI 默认映射器会把 record
     * 访问器当成 setterless 属性并抛异常。生产代码使用同样的 mapper 配置，这里直接覆盖两种异常形态。</p>
     */
    @Test
    void convertsResearchResultWithDuplicateDetailFields() {
        var mapper = JsonMapper.builder()
                .configure(MapperFeature.USE_GETTERS_AS_SETTERS, false)
                .build();
        String json = "{\"topicId\":\"x\",\"topic\":\"主题\",\"details\":["
                + "{\"id\":\"a\",\"title\":\"标题1\",\"questions\":[\"问题1\"],"
                + "\"questions\":[\"问题2\"],\"title\":\"标题2\"}]}";

        ResearchResult result = new BeanOutputConverter<>(ResearchResult.class, mapper)
                .convert(OpenAiCompatibleLlmService.normalizeJson(json));

        assertEquals("标题2", result.details().getFirst().title());
        assertEquals("问题2", result.details().getFirst().questions().getFirst());
    }

    /**
     * 各种用户可合理输入的地址都必须归一为不含 API 版本段的服务根地址。
     *
     * @param configuredUrl 配置文件或环境变量中的原始地址
     * @param expectedUrl 传给 Spring AI OpenAiApi 的目标根地址
     */
    @ParameterizedTest
    @MethodSource("baseUrlCases")
    void normalizesBaseUrlWithoutDuplicatingV1(String configuredUrl, String expectedUrl) {
        assertEquals(expectedUrl, OpenAiCompatibleLlmService.normalizeBaseUrl(configuredUrl));
    }

    /**
     * 提供基础地址、末尾斜杠、一个版本段和历史重复版本段四种回归案例。
     * 最后一种案例直接覆盖本次线上错误中的 {@code /v1/v1} 组合风险。
     */
    private static Stream<Arguments> baseUrlCases() {
        return Stream.of(
                Arguments.of("https://api.example.com", "https://api.example.com"),
                Arguments.of(" https://api.example.com/ ", "https://api.example.com"),
                Arguments.of("https://api.example.com/v1", "https://api.example.com"),
                Arguments.of("https://api.example.com/v1/v1/", "https://api.example.com"));
    }
}
