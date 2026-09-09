package com.cortex.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 子 Agent 工具过滤多层防线（F26-F31/AC5/AC6）。 */
class FilterTest {

    private static final List<String> ALL = List.of(
            "read_file", "write_file", "edit_file", "bash", "glob", "grep",
            "install_skill", "Agent", "TaskList", "TaskGet", "TaskStop", "SendMessage",
            "mcp__fs__read", "mcp__git__status");

    private static Filter.FilterParams params(boolean background, boolean fork,
                                              List<String> allowed, List<String> disallowed) {
        return new Filter.FilterParams(ALL, 1, background, fork, allowed, disallowed);
    }

    @Test
    void 默认只去除Agent元工具() {
        List<String> out = Filter.applyAgentToolFilter(params(false, false, List.of(), List.of()));
        assertFalse(out.contains("Agent"), "定义式子 Agent 看不到 Agent 工具（F3/AC6）");
        assertTrue(out.contains("read_file"));
        assertTrue(out.contains("TaskList"), "前台子 Agent 其余工具保留（本期全局禁止列表仅 Agent）");
        assertEquals(ALL.size() - 1, out.size());
        // 保持注册顺序
        assertEquals(ALL.stream().filter(n -> !"Agent".equals(n)).toList(), out);
    }

    @Test
    void 后台白名单收窄_元工具全部剔除() {
        List<String> out = Filter.applyAgentToolFilter(params(true, false, List.of(), List.of()));
        assertTrue(out.contains("read_file"));
        assertTrue(out.contains("write_file"));
        assertTrue(out.contains("edit_file"));
        assertTrue(out.contains("glob"));
        assertTrue(out.contains("grep"));
        assertTrue(out.contains("bash"));
        assertTrue(out.contains("install_skill"));
        assertFalse(out.contains("Agent"));
        assertFalse(out.contains("TaskList"));
        assertFalse(out.contains("TaskGet"));
        assertFalse(out.contains("TaskStop"));
        assertFalse(out.contains("SendMessage"));
    }

    @Test
    void 后台白名单放行MCP工具() {
        List<String> out = Filter.applyAgentToolFilter(params(true, false, List.of(), List.of()));
        assertTrue(out.contains("mcp__fs__read"), "MCP 工具按 mcp__ 前缀动态放行（F28）");
        assertTrue(out.contains("mcp__git__status"));
    }

    @Test
    void Fork保留完整父工具集含Agent() {
        List<String> out = Filter.applyAgentToolFilter(params(true, true, List.of(), List.of()));
        assertEquals(ALL, out, "Fork 工具集与父完全一致（N2 缓存一致/AC5）");
        assertTrue(out.contains("Agent"));
    }

    @Test
    void 黑名单剔除指定工具() {
        List<String> out = Filter.applyAgentToolFilter(params(false, false, List.of(), List.of("bash", "grep")));
        assertFalse(out.contains("bash"));
        assertFalse(out.contains("grep"));
        assertTrue(out.contains("read_file"));
    }

    @Test
    void 白名单收窄到指定集合() {
        List<String> out = Filter.applyAgentToolFilter(params(false, false,
                List.of("read_file", "grep"), List.of()));
        assertEquals(List.of("read_file", "grep"), out);
    }

    @Test
    void 白黑名单组合_白先收窄黑再剔除() {
        List<String> out = Filter.applyAgentToolFilter(params(false, false,
                List.of("read_file", "bash"), List.of("bash")));
        assertEquals(List.of("read_file"), out);
    }

    @Test
    void 后台加白名单取交集() {
        List<String> out = Filter.applyAgentToolFilter(params(true, false,
                List.of("read_file", "Agent", "bash"), List.of()));
        // Agent 不在后台白名单，交集后剔除
        assertEquals(List.of("read_file", "bash"), out);
    }

    @Test
    void isMcpTool边界() {
        assertTrue(Filter.isMcpTool("mcp__a__b"));
        assertFalse(Filter.isMcpTool("mcp_a"));
        assertFalse(Filter.isMcpTool("read_file"));
        assertFalse(Filter.isMcpTool(null));
        assertTrue(Filter.isAllowedInBackground("mcp__a__b"));
        assertTrue(Filter.isAllowedInBackground("bash"));
        assertFalse(Filter.isAllowedInBackground("Agent"));
    }
}
