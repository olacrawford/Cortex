package com.cortex.hook;

import com.cortex.permission.Matcher;

/** 原子条件（F12）：payload 字段路径 + 结构化匹配器。 */
public record AtomCondition(String field, Matcher matcher) {}
