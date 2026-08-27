package com.example.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层跨域配置。
 * 仅为本地 Vite 开发服务器开放 API/SSE，生产环境应由反向代理或受控域名替换，不能使用通配来源。
 */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    /**
     * 允许 React 开发服务器访问任务 API。
     * SSE 也是 GET 请求，因此与普通查询接口使用同一 CORS 映射；不开放 PUT/DELETE 等未实现方法。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins("http://localhost:5173").allowedMethods("GET", "POST").allowedHeaders("*");
    }
}
