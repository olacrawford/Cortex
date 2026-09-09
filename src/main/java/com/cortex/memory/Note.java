package com.cortex.memory;

/**
 * 一条持久化笔记（F28）：类型、标题、正文、创建/更新时间（ISO）。
 */
public record Note(String type, String title, String content, String created, String updated) {}
