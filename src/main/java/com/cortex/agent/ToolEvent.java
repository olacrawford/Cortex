package com.cortex.agent;

/**
 * 一次工具调用的开始/结束事件（供 TUI 渲染工具行与结果摘要）。
 */
public record ToolEvent(
        String name,     // 工具名
        String args,     // 参数预览（● name(args) 中的 args）
        Phase phase,
        String result,   // END：执行产出
        boolean isError  // END：是否为错误结果
) {}
