package com.cortex.hook;

/** 多原子条件组合方式（F11）：一条 hook 的条件顶层只能是二者之一，不允许嵌套混用。 */
public enum CombineMode {
    ALL_OF,
    ANY_OF
}
