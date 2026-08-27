package com.example.agent.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring Bean 配置，统一注册配置属性和受控并行执行器。 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfiguration {

    /**
     * 创建有界虚拟线程执行器。
     * 每个主题仍由任务级锁串行落盘，执行器只负责隐藏远程 LLM 延迟。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentExecutor(AgentProperties properties) {
        int parallelism = Math.max(1, properties.getExecutor().getParallelism());
        return Executors.newFixedThreadPool(parallelism);
    }
}
