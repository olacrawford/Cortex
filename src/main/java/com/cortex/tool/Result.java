package com.cortex.tool;

/**
 * 工具执行结果——永远以值类型返回，从不抛 checked exception。
 */
public record Result(String content, boolean isError) {

    public static Result ok(String content) {
        return new Result(content, false);
    }

    public static Result error(String content) {
        return new Result(content, true);
    }
}
