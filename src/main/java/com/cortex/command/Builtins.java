package com.cortex.command;

import java.util.List;

/** 12 条内置命令的统一注册入口（G1）：/help 的 handler 闭包捕获注册中心自身。 */
public final class Builtins {

    private Builtins() {}

    /** 按字典序注册全部 12 条内置命令；注册中心保证名字冲突在启动期立即报错（F2）。 */
    public static void registerAll(CommandRegistry reg) {
        reg.register(new Command("clear", List.of(), "清空当前会话并开启新会话",
                Kind.UI, false, BuiltinUi.clear()));
        reg.register(new Command("compact", List.of(), "手动压缩当前上下文",
                Kind.UI, false, BuiltinUi.compact()));
        reg.register(new Command("do", List.of(), "退出计划模式并开始执行计划",
                Kind.PROMPT, false, BuiltinPrompt.doRun()));
        reg.register(new Command("exit", List.of(), "退出 Cortex",
                Kind.UI, false, BuiltinUi.exit()));
        reg.register(new Command("help", List.of(), "查看全部可用命令",
                Kind.LOCAL, false, BuiltinLocal.help(reg)));
        reg.register(new Command("memory", List.of(), "查看已加载的记忆文件",
                Kind.LOCAL, false, BuiltinLocal.memory()));
        reg.register(new Command("permission", List.of(), "查看当前权限模式",
                Kind.LOCAL, false, BuiltinLocal.permission()));
        reg.register(new Command("plan", List.of(), "进入计划模式",
                Kind.UI, false, BuiltinUi.plan()));
        reg.register(new Command("resume", List.of(), "恢复历史会话",
                Kind.UI, false, BuiltinUi.resume()));
        reg.register(new Command("review", List.of(), "请求审查当前上下文中的代码变更",
                Kind.PROMPT, false, BuiltinPrompt.review()));
        reg.register(new Command("session", List.of(), "查看当前会话信息",
                Kind.LOCAL, false, BuiltinLocal.session()));
        reg.register(new Command("skills", List.of(), "列出已安装的技能",
                Kind.LOCAL, false, BuiltinLocal.skills()));
        reg.register(new Command("status", List.of(), "查看运行状态（模式/用量/工具/记忆/模型/目录）",
                Kind.LOCAL, false, BuiltinLocal.status()));
    }
}
