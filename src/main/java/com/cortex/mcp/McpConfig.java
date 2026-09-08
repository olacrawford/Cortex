package com.cortex.mcp;

import java.util.Map;

/**
 * mcp_servers 配置在内存中的归一化形式（已展开 ${VAR}、已合并两层、已校验）。
 *
 * @param servers server 名 → server 定义（LinkedHashMap，保持配置顺序）
 */
public record McpConfig(Map<String, ServerConfig> servers) {

    public McpConfig {
        servers = servers == null ? Map.of() : Map.copyOf(servers);
    }
}
