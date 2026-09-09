package com.cortex.llm;

/**
 * 上下文过长（prompt_too_long）哨兵异常。
 * 不同 provider 返回的具体错误结构差异大，统一成哨兵异常后 Agent 主循环只需一处判断
 * （{@code instanceof} / {@code getCause()}）。由于 {@link LlmClient#stream} 接口不抛 checked
 * exception，PTL 错误以 {@link StreamEvent.Error} 携带该 cause 投递到事件流。
 */
public class PromptTooLongException extends RuntimeException {

    public PromptTooLongException(Throwable cause) {
        super("prompt too long for context window", cause);
    }

    /** 判断异常链里是否出现「上下文过长」特征（消息含关键词）。 */
    public static boolean isPromptTooLong(Throwable e) {
        Throwable t = e;
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase();
                if (m.contains("prompt is too long")
                        || m.contains("context_length")
                        || m.contains("context length")
                        || m.contains("prompt_too_long")
                        || m.contains("maximum context length")
                        || m.contains("max context length")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }
}
