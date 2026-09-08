package com.cortex.mcp;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 配置加载（F1/F2/F3）：读取用户级与项目级两层 YAML 的 mcp_servers 段，
 * 按 server 名合并（项目级同名完整覆盖）、展开 env/headers 的 ${VAR}、逐个校验字段。
 * 任何缺失/非法都降级跳过并 stderr 告警，绝不抛异常致启动失败（N1/N2/N5）。
 */
public final class McpConfigLoader {

    private McpConfigLoader() {}

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");
    private static final PrintStream WARN = java.lang.System.err;

    /** 未展开前的原始 server 定义（字段均可空）。 */
    record RawServer(String type, String command, List<String> args,
                     Map<String, String> env, String url, Map<String, String> headers) {}

    /** ${VAR} 展开结果：展开后的串 + 未定义变量名列表。 */
    record Expansion(String out, List<String> undefined) {}

    // ─── 单文件加载 ───

    /**
     * 加载单个配置文件的 mcp_servers 段。文件不存在/格式非法 → 空 map（后者带告警）。
     */
    static Map<String, RawServer> loadFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            String text = Files.readString(path);
            Object loaded = new Yaml().load(new StringReader(text));
            if (!(loaded instanceof Map<?, ?> root)) {
                return Map.of();
            }
            Object servers = root.get("mcp_servers");
            if (!(servers instanceof Map<?, ?> serversMap)) {
                return Map.of(); // mcp_servers 缺失视为零个 server
            }
            return bindServers(serversMap);
        } catch (IOException | RuntimeException e) {
            WARN.printf("[mcp] warn: 配置文件 %s 加载失败，已跳过: %s%n", path, e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, RawServer> bindServers(Map<?, ?> serversMap) {
        Map<String, RawServer> result = new LinkedHashMap<>();
        for (var entry : serversMap.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> def)) {
                WARN.printf("[mcp] warn: skip server %s: 定义必须是键值映射%n", name);
                continue;
            }
            Map<String, Object> d = (Map<String, Object>) def;
            result.put(name, new RawServer(
                    str(d.get("type")),
                    str(d.get("command")),
                    strList(d.get("args")),
                    strMap(d.get("env")),
                    str(d.get("url")),
                    strMap(d.get("headers"))));
        }
        return result;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    private static List<String> strList(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }

    private static Map<String, String> strMap(Object o) {
        if (!(o instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : map.entrySet()) {
            if (e.getValue() instanceof String s) {
                out.put(String.valueOf(e.getKey()), s);
            }
        }
        return out;
    }

    // ─── ${VAR} 展开 ───


    static Expansion expandVars(String s) {
        if (s == null || s.isEmpty()) {
            return new Expansion(s == null ? "" : s, List.of());
        }
        Matcher m = VAR_PATTERN.matcher(s);
        StringBuilder sb = new StringBuilder();
        List<String> undefined = new ArrayList<>();
        while (m.find()) {
            String name = m.group(1);
            String value = java.lang.System.getenv(name);
            if (value == null) {
                value = "";
                undefined.add(name);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return new Expansion(sb.toString(), undefined);
    }

    /** 对 env / headers 的值做 ${VAR} 展开；未定义变量 stderr 告警（不阻断，N2/N6）。 */
    static RawServer applyExpansion(String name, RawServer srv) {
        Map<String, String> env = expandMap(name, "env", srv.env());
        Map<String, String> headers = expandMap(name, "headers", srv.headers());
        if (env.equals(srv.env()) && headers.equals(srv.headers())) {
            return srv;
        }
        return new RawServer(srv.type(), srv.command(), srv.args(), env, srv.url(), headers);
    }

    private static Map<String, String> expandMap(String serverName, String kind, Map<String, String> values) {
        if (values.isEmpty()) {
            return values;
        }
        Map<String, String> out = new LinkedHashMap<>();
        List<String> undefined = new ArrayList<>();
        for (var e : values.entrySet()) {
            Expansion ex = expandVars(e.getValue());
            out.put(e.getKey(), ex.out());
            for (String v : ex.undefined()) {
                if (!undefined.contains(v)) {
                    undefined.add(v);
                }
            }
        }
        for (String v : undefined) {
            WARN.printf("[mcp] warn: undefined env var ${%s} referenced by server %s (%s)%n", v, serverName, kind);
        }
        return out;
    }

    // ─── 合并与校验 ───

    /** 按 server 名合并：项目级同名完整覆盖用户级（F1）。 */
    static Map<String, RawServer> mergeServers(Map<String, RawServer> user, Map<String, RawServer> project) {
        Map<String, RawServer> merged = new LinkedHashMap<>(user);
        merged.putAll(project);
        return merged;
    }

    /** 字段校验（F2）：type 显式且必填字段齐全；违规 stderr 告警并剔除（N2）。 */
    static Optional<ServerConfig> validateServer(String name, RawServer srv) {
        String reason = null;
        if ("stdio".equals(srv.type())) {
            if (srv.command() == null || srv.command().isBlank()) {
                reason = "stdio 类型缺少 command";
            }
        } else if ("http".equals(srv.type())) {
            if (srv.url() == null || srv.url().isBlank()) {
                reason = "http 类型缺少 url";
            }
        } else {
            reason = "type 必须是 stdio 或 http";
        }
        if (reason != null) {
            WARN.printf("[mcp] warn: skip server %s: %s%n", name, reason);
            return Optional.empty();
        }
        return Optional.of(new ServerConfig(srv.type(), srv.command(), srv.args(), srv.env(), srv.url(), srv.headers()));
    }

    // ─── 对外入口 ───

    /**
     * 加载并合并两层配置（F1/F2/F3）：
     * 用户级 ~/.cortex/mcp.yaml + 项目级 <root>/.cortex/mcp.yaml（项目级同名完整覆盖）。
     * 永不抛异常。
     */
    public static McpConfig loadConfig(Path root) {
        Path userPath = Path.of(java.lang.System.getProperty("user.home"), ".cortex", "mcp.yaml");
        Path projectPath = root.resolve(".cortex").resolve("mcp.yaml");

        Map<String, RawServer> user = applyExpansionAll(loadFile(userPath));
        Map<String, RawServer> project = applyExpansionAll(loadFile(projectPath));
        Map<String, RawServer> merged = mergeServers(user, project);

        Map<String, ServerConfig> servers = new LinkedHashMap<>();
        for (var e : merged.entrySet()) {
            validateServer(e.getKey(), e.getValue()).ifPresent(sc -> servers.put(e.getKey(), sc));
        }
        return new McpConfig(servers);
    }

    private static Map<String, RawServer> applyExpansionAll(Map<String, RawServer> layer) {
        Map<String, RawServer> out = new LinkedHashMap<>();
        for (var e : layer.entrySet()) {
            out.put(e.getKey(), applyExpansion(e.getKey(), e.getValue()));
        }
        return out;
    }
}
