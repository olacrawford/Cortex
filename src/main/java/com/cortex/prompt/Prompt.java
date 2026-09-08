package com.cortex.prompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 系统提示装配：把模块按优先级拼成完整的稳定系统提示（可缓存通道，N1）。
 * 环境信息等变化内容不在此构造——见 {@link Environment}。
 */
public final class Prompt {

    /** 应用版本（banner 与环境信息共用）。 */
    public static final String VERSION = "0.1.0";

    private Prompt() {}

    /**
     * 按优先级升序装配模块；content 为空的模块跳过、不留多余空行（F1/AC2）。
     */
    public static String assembleSystem(List<Module> mods) {
        return mods.stream()
                .filter(m -> m.content() != null && !m.content().isEmpty())
                .sorted(Comparator.comparingInt(Module::priority))
                .map(Module::content)
                .collect(Collectors.joining("\n\n"));
    }

    /** 完整稳定系统提示 = 七个固定模块 + 可选空槽（当前为空，自动跳过）。 */
    public static String buildSystemPrompt() {
        List<Module> all = new ArrayList<>(Modules.fixedModules());
        all.addAll(Modules.optionalModules());
        return assembleSystem(all);
    }

    public static String renderBanner() {
        return """
                   ██████╗   ██████╗  ██████╗  ████████╗ ███████╗ ██╗  ██╗
                  ██╔════╝  ██╔═══██╗ ██╔══██╗ ╚══██╔══╝ ██╔════╝ ╚██╗██╔╝
                  ██║       ██║   ██║ ██████╔╝    ██║    █████╗    ╚███╔╝
                  ██║       ██║   ██║ ██╔══██╗    ██║    ██╔══╝    ██╔██╗
                  ╚██████╗  ╚██████╔╝ ██║  ██║    ██║    ███████╗ ██╔╝ ██╗
                   ╚═════╝   ╚═════╝  ╚═╝  ╚═╝    ╚═╝    ╚══════╝ ╚═╝  ╚═╝
                   Cortex v%s — LLM Terminal Coding Agent
                """.formatted(VERSION);
    }
}
