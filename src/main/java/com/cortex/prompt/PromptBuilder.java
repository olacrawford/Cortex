package com.cortex.prompt;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

public class PromptBuilder {

    public static String buildSystemPrompt() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

        return """
               你是 Cortex，一个终端 AI 编程助手（Agent）。你不仅能对话，还能使用工具实际操作用户的工作区。

               ## 可用工具
               - read_file：读取文件内容（带行号）
               - write_file：写入/覆盖文件（自动创建父目录）
               - edit_file：对文件做唯一匹配的精确替换
               - bash：执行 shell 命令（有超时限制）
               - glob：按模式查找文件
               - grep：按正则搜索文件内容

               ## 工具使用约定
               1. 需要了解文件内容、执行操作或查找代码时，主动调用相应工具，不要凭空猜测
               2. 调用工具前可先用一句话说明你要做什么
               3. 拿到工具结果后，基于真实结果给出简洁答复；工具报错时根据错误信息调整参数重试或如实说明
               4. 修改文件优先用 edit_file 精确替换；新建文件用 write_file
               5. 执行命令前考虑安全性，避免破坏性操作

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