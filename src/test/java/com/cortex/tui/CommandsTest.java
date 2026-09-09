package com.cortex.tui;

import com.cortex.agent.CompactEvent;
import com.cortex.agent.CompactPhase;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CommandsTest {

    @Test
    void 非斜杠输入返回空() {
        assertTrue(Commands.dispatchCommand("hello").isEmpty());
        assertTrue(Commands.dispatchCommand("").isEmpty());
        assertTrue(Commands.dispatchCommand(null).isEmpty());
    }

    @Test
    void 已注册命令返回处理器() {
        assertTrue(Commands.dispatchCommand("/exit").isPresent());
        assertTrue(Commands.dispatchCommand("/plan").isPresent());
        assertTrue(Commands.dispatchCommand("/do").isPresent());
        assertTrue(Commands.dispatchCommand("/compact").isPresent());
    }

    @Test
    void 未注册命令返回未知处理器() {
        Optional<Commands.CommandHandler> h = Commands.dispatchCommand("/unknown");
        assertTrue(h.isPresent(), "未知命令也应返回一个兜底 handler");
    }

    @Test
    void formatCompactNotice各阶段文案() {
        assertEquals("正在压缩上下文...",
                Commands.formatCompactNotice(new CompactEvent(CompactPhase.BEFORE_AUTO, 0, 0, null)));
        assertEquals("上下文撞墙,自动压缩中...",
                Commands.formatCompactNotice(new CompactEvent(CompactPhase.BEFORE_EMERGENCY, 0, 0, null)));
        assertEquals("已压缩,token 从 167000 降至 12000",
                Commands.formatCompactNotice(new CompactEvent(CompactPhase.AFTER_AUTO, 167000, 12000, null)));
        assertEquals("压缩失败:boom",
                Commands.formatCompactNotice(new CompactEvent(CompactPhase.AFTER_AUTO, 0, 0, new RuntimeException("boom"))));
    }
}
