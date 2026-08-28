package com.example.agent.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Bean 配置，统一注册配置属性和受控并行执行器。
 * 执行器由 Spring 在应用关闭时关闭，避免后台任务线程阻止 JVM 正常退出。
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfiguration {

    /**
     * 创建有界固定线程池。
     *
     * <p>逐项答案的远程模型调用以 I/O 等待为主，允许有限并行提高吞吐；不能使用无界线程池，
     * 否则包含大量 PlanItem 的任务会耗尽本机连接和模型限流配额。状态落盘仍由任务级锁串行化。</p>
     *
     * @param properties 并行度配置
     * @return 受 Spring 生命周期管理的执行器
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentExecutor(AgentProperties properties) {
        int parallelism = Math.max(1, properties.getExecutor().getParallelism());
        return Executors.newFixedThreadPool(parallelism);
    }
}
