package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动入口。
 *
 * <p>该应用以本地文件系统为持久化边界，启动时由恢复服务扫描未完成任务，
 * 并通过 StateGraph 驱动后续节点执行。</p>
 */
@SpringBootApplication
public class KnowledgeAgentApplication {

    /** 启动 Spring Boot 应用。 */
    public static void main(String[] args) {
        SpringApplication.run(KnowledgeAgentApplication.class, args);
    }
}
