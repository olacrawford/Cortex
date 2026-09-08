package com.cortex.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptTest {

    @Test
    void 固定模块按优先级装配且空行分隔() {
        String sys = Prompt.buildSystemPrompt();
        // 身份段在工具使用段之前（AC1）
        assertTrue(sys.indexOf("terminal coding agent") < sys.indexOf("Prefer the dedicated tools"));
        // 模块间以空行分隔：不含三个连续换行（空槽跳过后不留多余空行，AC2）
        assertFalse(sys.contains("\n\n\n"));
        // 七个固定模块全部出现
        for (String fragment : new String[]{
                "terminal coding agent", "Never print", "ReAct steps", "Consecutive",
                "Prefer the dedicated tools", "No flattery", "Use Markdown"}) {
            assertTrue(sys.contains(fragment), "缺少模块内容: " + fragment);
        }
    }

    @Test
    void 空槽自动跳过() {
        String s = Prompt.assembleSystem(List.of(
                new Module("a", 1, "A"),
                new Module("empty", 2, ""),
                new Module("c", 3, "C")));
        assertEquals("A\n\nC", s);
    }

    @Test
    void 挂载即扩展_新模块按优先级插入() {
        List<Module> mods = List.of(
                new Module("a", 10, "AAA"),
                new Module("mid", 15, "MID"), // 挂载在两者之间
                new Module("b", 20, "BBB"));
        assertEquals("AAA\n\nMID\n\nBBB", Prompt.assembleSystem(mods));
    }

    @Test
    void 稳定提示逐字节确定且不含环境成分() {
        String s1 = Prompt.buildSystemPrompt();
        String s2 = Prompt.buildSystemPrompt();
        assertEquals(s1, s2); // N1：两次构造逐字节相等
        assertFalse(s1.contains(java.time.LocalDate.now().toString())); // 不含日期
        assertFalse(s1.contains("环境信息")); // 环境段不属于稳定块
    }

    @Test
    void 双重强化_系统提示含关键约定() {
        String sys = Prompt.buildSystemPrompt();
        assertTrue(sys.contains("Prefer the dedicated tools (read_file, glob, grep)")); // 优先专用工具
        assertTrue(sys.contains("Before editing a file you must read it first")); // 编辑前必先读
    }
}
