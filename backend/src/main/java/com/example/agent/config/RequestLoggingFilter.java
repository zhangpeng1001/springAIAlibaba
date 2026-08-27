package com.example.agent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * API 请求生命周期日志过滤器。
 *
 * <p>每个请求生成或沿用 {@code X-Request-Id}，并放入 SLF4J MDC。这样 Controller、异常处理器等
 * 同线程日志会带上同一关联编号，能够从“用户触发了什么操作”追到“接口返回了什么错误”。
 * 不记录请求体，避免问题文本、访问密钥或未来新增的敏感字段进入普通业务日志。</p>
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    /** 统一 HTTP 请求日志，日志格式会自动追加 MDC 内的 requestId。 */
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /**
     * 仅跟踪本系统的任务 API。
     * 静态资源、浏览器图标和健康检查不属于业务工作流，跳过它们可避免日志被无关请求淹没。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    /**
     * 记录请求进入与返回的关键元数据，并向客户端回传关联编号。
     *
     * <p>无论 Controller、参数校验或全局异常处理器中的哪一层抛出异常，finally 都会输出状态码和耗时；
     * 这解决了异步任务以外的 HTTP 失败难以判断“请求是否进入系统”的问题。</p>
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param filterChain 后续过滤器和 MVC 处理链
     * @throws ServletException Servlet 容器处理失败时透传
     * @throws IOException 网络输出失败时透传
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        try {
//            log.info("HTTP 请求开始：method={}，uri={}，query={}，remoteAddress={}", request.getMethod(),
//                    request.getRequestURI(), request.getQueryString(), request.getRemoteAddr());
            filterChain.doFilter(request, response);
        } finally {
//            log.info("HTTP 请求结束：method={}，uri={}，status={}，durationMs={}", request.getMethod(),
//                    request.getRequestURI(), response.getStatus(), Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
            MDC.remove("requestId");
        }
    }
}
