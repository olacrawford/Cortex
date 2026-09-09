package com.cortex.hook;

import java.time.Duration;

/**
 * 一条已编译的 hook 规则（F8）：事件 + 可省条件 + 动作 + 执行控制。
 *
 * @param source   加载来源文件路径（日志与 /hooks 展示用）
 */
public record HookRule(
        String name,
        Event event,
        Condition condition,
        Action action,
        boolean onlyOnce,
        boolean async,
        Duration timeout,
        String source) {

    /** 缺省超时（F8/F18：30 秒）。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** flags 串（/hooks 列表用）：`[once]` / `[async]`。 */
    public String flags() {
        String f = (onlyOnce ? " [once]" : "") + (async ? " [async]" : "");
        return f.strip();
    }
}
