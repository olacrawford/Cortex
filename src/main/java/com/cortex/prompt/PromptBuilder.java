package com.cortex.prompt;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

public class PromptBuilder {

    public static String buildSystemPrompt() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

        return """
               你是 Cortex，一个终端 AI 编程助手。

               ## 核心能力
               你运行在用户的终端中，可以执行命令、读写文件、搜索代码、浏览网页等。
               你的目标是帮助用户完成编程任务，从简单的代码生成到复杂项目构建。

               ## 行为准则
               1. 在生成代码/命令前，先理解用户的真实需求
               2. 对于复杂任务，先给出方案再执行
               3. 遇到安全问题要提醒用户
               4. 保持回复简洁专业

               ## 环境信息
               - 操作系统: %s
               - 架构: %s
               - Java 版本: %s
               """
                .formatted(
                        os.getName(),
                        os.getArch(),
                        System.getProperty("java.version")
                );
    }

    public static String renderBanner() {
        return """
                   ██████╗   ██████╗  ██████╗  ████████╗ ███████╗ ██╗  ██╗
                  ██╔════╝  ██╔═══██╗ ██╔══██╗ ╚══██╔══╝ ██╔════╝ ╚██╗██╔╝
                  ██║       ██║   ██║ ██████╔╝    ██║    █████╗    ╚███╔╝
                  ██║       ██║   ██║ ██╔══██╗    ██║    ██╔══╝    ██╔██╗
                  ╚██████╗  ╚██████╔╝ ██║  ██║    ██║    ███████╗ ██╔╝ ██╗
                   ╚═════╝   ╚═════╝  ╚═╝  ╚═╝    ╚═╝    ╚══════╝ ╚═╝  ╚═╝
                   Cortex v0.1.0 — LLM Terminal Chat Client
                """;
    }
}