package com.cortex.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 子 Agent 工具过滤多层防线（F26-F31）：按固定顺序合并全局禁止列表、后台白名单、
 * Agent 定义的黑/白名单，产出子 Agent 最终可见的工具名列表。
 * 过滤只发生在子 Agent 构造时，主 Agent 看到的工具列表不变（N1，防 prompt cache 抖动）。
 */
public final class Filter {

    /** 任何子 Agent 永远不能用的工具（F26；阶段14 加入 Team 专属协作工具）。 */
    public static final List<String> ALL_AGENT_DISALLOWED_TOOLS =
            List.of("Agent", "TaskCreate", "TaskUpdate");

    /** Team 队员专属协作工具（F26/G6）：teammate=true 时从禁止集中豁免。 */
    public static final List<String> TEAMMATE_TOOLS = List.of("TaskCreate", "TaskUpdate");

    /**
     * 自定义（user/project/plugin 来源）Agent 额外禁用的工具（F27）。
     * 本期为空，接口预留。
     */
    public static final List<String> CUSTOM_AGENT_DISALLOWED_TOOLS = List.of();

    /**
     * 后台 Agent 工具白名单（F28）：只列基础读写搜索执行工具；
     * MCP 工具按 mcp__ 前缀动态放行（见 isAllowedInBackground）。
     * 不含 Agent / TaskList / TaskGet / TaskStop / SendMessage 等任何元工具。
     */
    public static final List<String> ASYNC_AGENT_ALLOWED_TOOLS = List.of(
            "read_file", "write_file", "edit_file",
            "glob", "grep",
            "bash",
            "install_skill");

    private Filter() {}

    /**
     * 过滤参数。
     *
     * @param all         registry 的全部工具名（按注册顺序）
     * @param source      定义来源：1=builtin 2=user 3=project 4=plugin（Source.ordinal()+1）
     * @param background  是否后台（run_in_background / 定义 background；Fork 强制后台不计入——Fork 需保留完整工具集）
     * @param fork        是否 Fork 路径（保留完整父工具集含 Agent 工具，靠双闸拦截，AC5/N2）
     * @param allowed     Agent 定义 tools 白名单（空 = 不收窄）
     * @param disallowed  Agent 定义 disallowedTools 黑名单
     * @param teammate    是否 Team 队员（true 时 TaskCreate/TaskUpdate 从禁止集豁免，G6/AC9）
     */
    public record FilterParams(
            List<String> all,
            int source,
            boolean background,
            boolean fork,
            List<String> allowed,
            List<String> disallowed,
            boolean teammate) {

        /** 兼容旧签名（非 Team 场景）。 */
        public FilterParams(List<String> all, int source, boolean background, boolean fork,
                            List<String> allowed, List<String> disallowed) {
            this(all, source, background, fork, allowed, disallowed, false);
        }
    }

    /** 按 F30 顺序应用五层过滤，返回最终 allowed 列表。 */
    public static List<String> applyAgentToolFilter(FilterParams p) {
        List<String> cur = new ArrayList<>(p.all());
        // ① 全局禁止列表（Fork 路径豁免：工具集保持与父一致，N2；Team 队员豁免协作工具，G6）
        if (!p.fork()) {
            if (p.teammate()) {
                cur.removeAll(ALL_AGENT_DISALLOWED_TOOLS.stream()
                        .filter(t -> !TEAMMATE_TOOLS.contains(t)).toList());
            } else {
                cur.removeAll(ALL_AGENT_DISALLOWED_TOOLS);
            }
        }
        // ② 自定义 Agent 额外限制（本期为空）
        if (p.source() >= 2) {
            cur.removeAll(CUSTOM_AGENT_DISALLOWED_TOOLS);
        }
        // ③ 后台白名单交集（Fork 豁免理由同上：Fork 工具集与父完全一致，N2/AC5）
        if (p.background() && !p.fork()) {
            cur.removeIf(name -> !isAllowedInBackground(name));
        }
        // ④ 定义黑名单
        cur.removeAll(p.disallowed());
        // ⑤ 定义白名单（空白名单 = 不再收窄）
        if (!p.allowed().isEmpty()) {
            cur.retainAll(p.allowed());
        }
        return List.copyOf(cur);
    }

    /** 后台白名单判定：基础工具 + MCP 工具（mcp__ 前缀）动态放行（F28）。 */
    public static boolean isAllowedInBackground(String name) {
        return ASYNC_AGENT_ALLOWED_TOOLS.contains(name) || isMcpTool(name);
    }

    /** MCP 工具命名约定识别（phase-06：mcp__&lt;server&gt;__&lt;tool&gt;）。 */
    public static boolean isMcpTool(String name) {
        return name != null && name.startsWith("mcp__");
    }
}
