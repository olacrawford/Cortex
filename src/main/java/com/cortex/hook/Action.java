package com.cortex.hook;

import java.util.Map;

/**
 * Hook 动作（F16）：sealed 四类——
 * <ul>
 *   <li>{@link Shell}：{@code sh -c} 执行命令，payload JSON 走 stdin（F17）；拦截事件下 exit 2 表达拦截（F19）</li>
 *   <li>{@link Prompt}：把 text 加入下一轮 reminder 队列，永不拦截（F20/F22）</li>
 *   <li>{@link Http}：发 HTTP 请求，body 支持 {@code ${field}} 模板（F23）；拦截事件下 2xx + decision=block 表达拦截（F25）</li>
 *   <li>{@link Subagent}：本期占位（F26），执行时仅记 stderr 日志</li>
 * </ul>
 */
public sealed interface Action permits Action.Shell, Action.Prompt, Action.Http, Action.Subagent {

    /** 动作类型名（stderr 日志与 /hooks 列表用）。 */
    String typeName();

    record Shell(String command) implements Action {
        @Override
        public String typeName() {
            return "shell";
        }
    }

    record Prompt(String text) implements Action {
        @Override
        public String typeName() {
            return "prompt";
        }
    }

    /**
     * @param method  缺省 POST（F23）
     * @param headers 可选键值对
     * @param body    可选模板；缺省时序列化整个 payload（F23）
     */
    record Http(String url, String method, Map<String, String> headers, String body) implements Action {
        public Http {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        @Override
        public String typeName() {
            return "http";
        }
    }

    record Subagent(String agentName, String prompt) implements Action {
        @Override
        public String typeName() {
            return "subagent";
        }
    }
}
