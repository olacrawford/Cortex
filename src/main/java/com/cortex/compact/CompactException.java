package com.cortex.compact;

/** 上下文管理的 checked exception 基类。 */
public class CompactException extends Exception {

    public CompactException(String message) {
        super(message);
    }

    public CompactException(String message, Throwable cause) {
        super(message, cause);
    }
}
