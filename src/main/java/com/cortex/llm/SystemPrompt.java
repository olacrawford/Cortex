package com.cortex.llm;

/**
 * 系统提示的两段内容（F2/F3）：
 * stable 为可缓存的稳定系统模块（跨轮逐字节一致，N1）；
 * environment 为每轮变化的环境信息段（不进缓存）。
 * 命名避开 System（与 java.lang.System 冲突）。
 */
public record SystemPrompt(String stable, String environment) {}
