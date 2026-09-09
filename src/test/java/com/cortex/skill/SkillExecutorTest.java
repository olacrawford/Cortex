package com.cortex.skill;

import com.cortex.conversation.Message;
import com.cortex.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class SkillExecutorTest {

    private static final ToolRegistry REGISTRY = ToolRegistry.createDefault();

    private static Skill skill(List<String> allowedTools, String mode, String forkContext) {
        return new Skill(new SkillMeta("demo", "演示", null, List.of(), allowedTools, mode, null, forkContext),
                "步骤一。$ARGUMENTS", null, true);
    }

    /** 可观测 SkillHost 桩。 */
    private static class RecordingHost implements SkillHost {
        String activatedName;
        String activatedBody;
        Predicate<String> filter;
        final ToolRegistry registry;

        RecordingHost(ToolRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void activateSkill(String name, String body) {
            activatedName = name;
            activatedBody = body;
        }

        @Override
        public void setToolFilter(Predicate<String> allowed) {
            this.filter = allowed;
        }

        @Override
        public ToolRegistry toolRegistry() {
            return registry;
        }
    }

    /** 可观测 SkillForkHost 桩。 */
    private static final class RecordingForkHost extends RecordingHost implements SkillForkHost {
        String forkBody;
        List<Message> forkSeed;
        List<String> forkAllowed;
        String forkModel;
        final List<Message> parentMessages = new ArrayList<>();
        String subAgentResult = "子 Agent 完成";

        RecordingForkHost(ToolRegistry registry) {
            super(registry);
        }

        @Override
        public String runSubAgent(String body, List<Message> seed, List<String> allowedTools, String model) {
            forkBody = body;
            forkSeed = seed;
            forkAllowed = allowedTools;
            forkModel = model;
            return subAgentResult;
        }

        @Override
        public List<Message> snapshotParentMessages() {
            return parentMessages;
        }
    }

    // ─── executeInline ───

    @Test
    void executeInline_顺序与返回值() {
        RecordingHost host = new RecordingHost(REGISTRY);
        String rendered = SkillExecutor.executeInline(skill(List.of("read_file"), "inline", "none"), "参数 X", host);
        assertEquals("demo", host.activatedName);
        assertTrue(host.activatedBody.contains("步骤一"));
        assertEquals("步骤一。参数 X", rendered, "返回渲染后的 body（F6）");
        assertNotNull(host.filter, "allowed_tools 非空时应通知宿主设置过滤器（F6）");
        assertTrue(host.filter.test("read_file"));
        assertFalse(host.filter.test("bash"));
    }

    @Test
    void executeInline_无白名单不设过滤器() {
        RecordingHost host = new RecordingHost(REGISTRY);
        SkillExecutor.executeInline(skill(List.of(), "inline", "none"), "", host);
        assertNull(host.filter);
    }

    @Test
    void executeInline_白名单含未注册工具抛IllegalState() {
        RecordingHost host = new RecordingHost(REGISTRY);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SkillExecutor.executeInline(skill(List.of("不存在的工具"), "inline", "none"), "", host));
        assertTrue(ex.getMessage().contains("不存在的工具"));
        assertNull(host.activatedName, "校验失败不得进入激活（N5）");
    }

    // ─── executeFork ───

    @Test
    void executeFork_种子与子Agent回传() {
        RecordingForkHost host = new RecordingForkHost(REGISTRY);
        for (int i = 1; i <= 7; i++) {
            host.parentMessages.add(new Message(Message.Role.USER, "历史消息 " + i));
        }
        String result = SkillExecutor.executeFork(
                skill(List.of("read_file"), "fork", "recent"), "请分析", host);
        assertEquals("子 Agent 完成", result);
        assertTrue(host.forkBody.contains("步骤一"));
        assertEquals(5, host.forkSeed.size(), "recent 种子取尾 5 条（F9）");
        assertEquals("历史消息 3", host.forkSeed.get(0).getContent());
        assertEquals("历史消息 7", host.forkSeed.get(4).getContent());
        assertEquals(List.of("read_file"), host.forkAllowed);
    }

    // ─── substituteArguments ───

    @Test
    void substituteArguments三分支() {
        assertEquals("原样正文", SkillExecutor.substituteArguments("原样正文", "   "),
                "args 空白原样返回（F8）");
        assertEquals("结果是 X", SkillExecutor.substituteArguments("结果是 $ARGUMENTS", "X"),
                "占位符替换（F8）");
        assertEquals("结果是 X 和 X", SkillExecutor.substituteArguments("结果是 $ARGUMENTS 和 $ARGUMENTS", "X"),
                "全部占位符替换（F8）");
        assertEquals("无占位符\n\n## User Request\n\n问题",
                SkillExecutor.substituteArguments("无占位符", "问题"),
                "无占位符追加 ## User Request 段（F8）");
        assertEquals("原样正文", SkillExecutor.substituteArguments("原样正文", null));
        assertEquals("", SkillExecutor.substituteArguments(null, "args"));
    }

    // ─── buildForkSeed ───

    @Test
    void buildForkSeed_none_recent_full() {
        List<Message> parent = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            parent.add(new Message(Message.Role.USER, "m" + i));
        }
        assertTrue(SkillExecutor.buildForkSeed("none", parent).isEmpty(), "none 返回空（F9）");
        assertTrue(SkillExecutor.buildForkSeed(null, parent).isEmpty(), "未知值返回空（F9）");
        assertTrue(SkillExecutor.buildForkSeed("recent", null).isEmpty());
        assertEquals(5, SkillExecutor.buildForkSeed("recent", parent).size());
        assertEquals("m3", SkillExecutor.buildForkSeed("recent", parent).get(0).getContent());
        assertEquals(7, SkillExecutor.buildForkSeed("full", parent).size(), "full 全量拷贝（F9）");
    }
}
