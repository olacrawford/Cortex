package com.cortex.mcp;

import java.util.List;
import java.util.Map;

/**
 * 单个 MCP server 的完整定义（F2）。
 *
 * @param type    "stdio" | "http"（显式声明，不靠字段嗅探）
 * @param command stdio 必填：子进程命令
 * @param args    stdio 可选：子进程参数
 * @param env     stdio 可选：注入子进程的环境变量（已展开 ${VAR}，同名覆盖宿主）
 * @param url     http 必填：Streamable HTTP endpoint
 * @param headers http 可选：注入每次请求的头（已展开 ${VAR}，用于鉴权）
 */
public record ServerConfig(
        String type,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        Map<String, String> headers) {

    public ServerConfig {
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
