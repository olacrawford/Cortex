package com.cortex.agent;

/**
 * Agent Loop 对外的事件流；sealed 让 TUI 按变体分派渲染。
 * 以 {@link Done} 作为终止事件（任何结束路径都以 Done 收尾）。
 */
public sealed interface AgentEvent
        permits AgentEvent.Text, AgentEvent.Tool, AgentEvent.UsageReport, AgentEvent.Iter,
                AgentEvent.Notice, AgentEvent.Approval, AgentEvent.Done, AgentEvent.Failed,
                CompactEvent {

    /** 文本增量（preamble 或最终答复）。 */
    record Text(String delta) implements AgentEvent {}

    /** 工具调用开始/结束。 */
    record Tool(ToolEvent event) implements AgentEvent {}

    /** 本轮请求的 token 用量（每轮 stream 结束后一次）。 */
    record UsageReport(Usage usage) implements AgentEvent {}

    /** 进入第 iter 轮迭代（进度提示）。 */
    record Iter(int iter) implements AgentEvent {}

    /** 系统提示（停止原因等）；仅 UI 展示，不入历史。 */
    record Notice(String message) implements AgentEvent {}

    /** 人在回路：工具调用等待用户批准（三选一，F8）。消费者必须回传 Outcome 解阻塞。 */
    record Approval(ApprovalRequest request) implements AgentEvent {}

    /** 本轮（整个 Loop）结束。 */
    record Done() implements AgentEvent {}

    /** 出错（会话不中断，可继续提问）。 */
    record Failed(String message) implements AgentEvent {}
}
