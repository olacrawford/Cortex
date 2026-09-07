package com.cortex.agent;

/**
 * 单轮闭环对外的事件流；sealed 让 TUI 按变体分派渲染。
 * 以 {@link Done} / {@link Failed} 作为终止事件。
 */
public sealed interface AgentEvent {

    /** 文本增量（preamble 或最终答复）。 */
    record Text(String delta) implements AgentEvent {}

    /** 工具调用开始/结束。 */
    record Tool(ToolEvent event) implements AgentEvent {}

    /** 本轮结束（单轮闭环完整走完）。 */
    record Done() implements AgentEvent {}

    /** 出错（会话不中断，可继续提问）。 */
    record Failed(String message) implements AgentEvent {}
}
