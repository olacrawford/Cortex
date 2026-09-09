package com.cortex.hook;

import com.cortex.agent.CancelToken;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 四类动作执行器（F17-F26）。所有失败一律以 {@link ExecutionResult#error} 返回——
 * hook 自身失败只记日志、不中断 Agent 主流程（G9），除非拦截事件下通过约定信号表达拦截：
 * <ul>
 *   <li>shell：exit code 2 → blocked，stderr（缺省 stdout）为拒绝原因（F19）</li>
 *   <li>http：2xx 且 body 为 {@code {"decision":"block","reason":…}} → blocked（F25）</li>
 *   <li>prompt：仅注入，永不拦截（F22）</li>
 *   <li>subagent：本期占位，仅记 stderr 日志（F26/N8）</li>
 * </ul>
 */
public class HookExecutor {

    private static final Pattern TEMPLATE_FIELD = Pattern.compile("\\$\\{([^}]+)}");
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final HttpClient httpClient;

    public HookExecutor() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** 执行单条动作；deadline 为该 hook 配置的 timeout（F18）。 */
    ExecutionResult run(HookRule rule, Payload payload, boolean blocking, CancelToken cancel) {
        Duration timeout = rule.timeout() == null ? HookRule.DEFAULT_TIMEOUT : rule.timeout();
        return switch (rule.action()) {
            case Action.Shell sa -> runShell(sa, payload, blocking, cancel, timeout);
            case Action.Prompt pa -> runPrompt(pa);
            case Action.Http ha -> runHttp(ha, payload, blocking, cancel, timeout);
            case Action.Subagent sa -> runSubagent(sa);
        };
    }

    // ─── shell ───

    private ExecutionResult runShell(Action.Shell sa, Payload payload, boolean blocking,
                                     CancelToken cancel, Duration timeout) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", sa.command());
            pb.redirectErrorStream(false);
            process = pb.start();
            // payload 序列化成单行 JSON 写 stdin（F17），写完即关避免命令等输入；
            // 命令不读 stdin（如 echo）秒退时管道破裂属正常，吞掉写失败
            try (var stdin = process.getOutputStream()) {
                stdin.write(payload.toSortedJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException ignored) {
                // broken pipe / stream closed
            }
            // 用独立线程排干输出管道，防止缓冲区满卡死子进程
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            final Process proc = process;
            Thread drainOut = Thread.ofVirtual().start(() -> drain(proc.getInputStream(), out));
            Thread drainErr = Thread.ofVirtual().start(() -> drain(proc.getErrorStream(), err));

            long deadline = System.nanoTime() + timeout.toNanos();
            Integer code = null;
            while (code == null) {
                if (cancel != null && cancel.isCancelled()) {
                    process.destroyForcibly();
                    return ExecutionResult.failed(new IOException("cancelled"));
                }
                if (System.nanoTime() > deadline) {
                    process.destroyForcibly();
                    return ExecutionResult.failed(new IOException(
                            "timeout after " + timeout.toSeconds() + "s"));
                }
                code = process.waitFor(50, TimeUnit.MILLISECONDS) ? process.exitValue() : null;
            }
            drainOut.join(1000);
            drainErr.join(1000);
            // hook 自身 stderr 转发到应用 stderr（checklist 场景 6 的观察通道）
            if (!err.isEmpty()) {
                System.err.print(err);
            }

            if (blocking && code == 2) {
                // 拦截命中：stderr 优先，缺省用 stdout；去尾换行（F19）
                String reason = (!err.isEmpty() ? err : out).toString().strip();
                return ExecutionResult.blocked(reason);
            }
            if (code == 0) {
                return ExecutionResult.empty();
            }
            return ExecutionResult.failed(new IOException(
                    "exit " + code + (!err.isEmpty() ? ": " + err.toString().strip() : "")));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return ExecutionResult.failed(e);
        } catch (IOException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return ExecutionResult.failed(e);
        }
    }

    private static void drain(java.io.InputStream in, StringBuilder sink) {
        try (in) {
            sink.append(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 子进程被强杀时读流失败属正常
        }
    }

    // ─── prompt ───

    private ExecutionResult runPrompt(Action.Prompt pa) {
        return ExecutionResult.prompt(pa.text());
    }

    // ─── http ───

    private ExecutionResult runHttp(Action.Http ha, Payload payload, boolean blocking,
                                    CancelToken cancel, Duration timeout) {
        try {
            String body = ha.body() == null || ha.body().isBlank()
                    ? payload.toSortedJson()
                    : renderTemplate(ha.body(), payload);
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(ha.url()))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .method(ha.method() == null || ha.method().isBlank() ? "POST" : ha.method(),
                            HttpRequest.BodyPublishers.ofString(body));
            for (Map.Entry<String, String> h : ha.headers().entrySet()) {
                rb.header(h.getKey(), h.getValue());
            }
            HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return ExecutionResult.failed(new IOException("HTTP " + resp.statusCode()));
            }
            if (blocking) {
                var node = MAPPER.readTree(resp.body());
                if ("block".equalsIgnoreCase(node.path("decision").asText())) {
                    return ExecutionResult.blocked(node.path("reason").asText(""));
                }
            }
            return ExecutionResult.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionResult.failed(e);
        } catch (Exception e) {
            // 网络错误 / 超时 / JSON 解析失败：hook 失败但不拦截（F25）
            return ExecutionResult.failed(e);
        }
    }

    /** {@code ${field}} / {@code ${nested.path}} 模板渲染（N10：只做最基本字段访问）。 */
    static String renderTemplate(String template, Payload payload) {
        Matcher m = TEMPLATE_FIELD.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = payload.getByPath(m.group(1).strip());
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ─── subagent（占位，F26/N8）───

    private ExecutionResult runSubagent(Action.Subagent sa) {
        System.err.printf("[hook subagent] not yet implemented, skipped: %s%n", sa.agentName());
        return ExecutionResult.empty();
    }
}
