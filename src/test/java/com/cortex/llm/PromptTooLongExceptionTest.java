package com.cortex.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptTooLongExceptionTest {

    @Test
    void 识别上下文过长关键词() {
        assertTrue(PromptTooLongException.isPromptTooLong(new RuntimeException("prompt is too long")));
        assertTrue(PromptTooLongException.isPromptTooLong(new RuntimeException("context_length_exceeded")));
        assertTrue(PromptTooLongException.isPromptTooLong(new RuntimeException("maximum context length")));
        assertTrue(PromptTooLongException.isPromptTooLong(
                new RuntimeException(new RuntimeException("prompt_too_long"))));
    }

    @Test
    void 不误判其他错误() {
        assertFalse(PromptTooLongException.isPromptTooLong(new RuntimeException("connection reset")));
        assertFalse(PromptTooLongException.isPromptTooLong(new RuntimeException("bad request")));
        assertFalse(PromptTooLongException.isPromptTooLong(null));
    }

    @Test
    void 哨兵异常携带cause() {
        RuntimeException cause = new RuntimeException("x");
        PromptTooLongException e = new PromptTooLongException(cause);
        assertSame(cause, e.getCause());
        assertTrue(e.getMessage().contains("prompt too long"));
    }
}
