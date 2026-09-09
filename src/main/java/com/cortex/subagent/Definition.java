package com.cortex.subagent;

import com.cortex.permission.Mode;

import java.util.List;

/**
 * 一个 Agent 角色的完整定义（F4），从 Markdown + YAML frontmatter 解析。
 * 正文（去 frontmatter 后的全文）作为子 Agent 的系统提示。
 *
 * @param name            角色名（subagent_type），小写字母/数字/连字符，1-32
 * @param description     一句话描述，供 Agent 工具的 subagent_type 文档与 UI 列表
 * @param tools           工具白名单；空表示不收窄
 * @param disallowedTools 工具黑名单
 * @param model           "haiku" / "sonnet" / "opus" / "inherit"；缺省 inherit（本期不做 provider 切换，仅记录）
 * @param maxTurns        最大迭代轮数；0 表示沿用全局默认（25）
 * @param permissionMode  子 Agent 权限模式；dontAsk 单独记入 dontAsk 字段、此处为 DEFAULT
 * @param dontAsk         子 Agent 专属兜底：规则未命中的 Ask 决策自动放行
 * @param background      true 时 Agent 工具忽略 run_in_background 参数、强制后台
 * @param isolation       文件系统隔离方式："" 无隔离 / "worktree" Git Worktree 隔离（阶段13 G12）
 * @param systemPrompt    正文全文（子 Agent 系统提示）
 * @param filePath        定义文件绝对路径 / classpath uri（调试用）
 * @param source          来源层级
 */
public record Definition(
        String name,
        String description,
        List<String> tools,
        List<String> disallowedTools,
        String model,
        int maxTurns,
        Mode permissionMode,
        boolean dontAsk,
        boolean background,
        String isolation,
        String systemPrompt,
        String filePath,
        Source source) {

    /** 合法的 isolation 取值（F20）。 */
    public static final String ISOLATION_WORKTREE = "worktree";

    /** Fork 路径占位角色名。 */
    public static final String FORK_NAME = "__fork__";

    /** 是否为 Fork 路径的临时定义（forkDefinition 生成）。 */
    public boolean isFork() {
        return FORK_NAME.equals(name);
    }
}
