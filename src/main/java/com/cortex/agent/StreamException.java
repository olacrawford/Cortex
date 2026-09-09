package com.cortex.agent;

/**
 * 流式请求异常（checked）。由 Agent.streamOnce 在收到 {@link com.cortex.llm.PromptTooLongException}
 * 时抛出，主循环据此进入紧急压缩 + 一次性重试路径。
 */
public class StreamException extends Exception {

    public StreamException(Throwable cause) {
        super(cause);
    }
}
