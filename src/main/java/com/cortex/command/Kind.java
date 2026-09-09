package com.cortex.command;

/**
 * 命令执行类型（F8）：
 * <ul>
 *   <li>{@code LOCAL} 纯本地——只输出信息，不改对话历史与运行模式，不消耗 token，任何状态可执行</li>
 *   <li>{@code UI} 影响界面——改运行状态/清空会话/退出进程，不向对话历史追加 user 消息，仅 idle 可执行</li>
 *   <li>{@code PROMPT} 提示词——向对话历史追加固定文本 user 消息并立即触发 LLM 回合，仅 idle 可执行</li>
 * </ul>
 */
public enum Kind {
    LOCAL,
    UI,
    PROMPT
}
