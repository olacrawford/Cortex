package com.cortex.agent;

import com.cortex.permission.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 队员邮箱摄取（T20/T32/F41/F42/AC16）。 */
class TeamMailboxIngestorTest {

    @TempDir
    Path tmp;

    /** 最小 Agent 桩：runtime 来自 SessionRuntime.empty，权限模式切换经 overrideMode 可观察。 */
    private Agent agent() {
        return new Agent(new com.cortex.llm.LlmClient() {
            @Override
            public java.util.concurrent.BlockingQueue<com.cortex.llm.StreamEvent> stream(com.cortex.llm.Request req) {
                throw new UnsupportedOperationException();
            }
        }, new com.cortex.tool.ToolRegistry(), "t",
                com.cortex.permission.PermissionEngine.create(tmp), SessionRuntime.empty(200000));
    }

    @Test
    void 未读消息注入reminder并标记已读() {
        Agent agent = agent();
        AtomicReference<List<Integer>> marked = new AtomicReference<>();
        TeammateContext tc = new TeammateContext("demo", "alice", "agent-a",
                () -> new TeammateContext.ReadUnreadView(List.of(0, 1), List.of(
                        new TeammateContext.IncomingMessage("lead", "text", "interface change",
                                "改一下接口签名", null, null),
                        new TeammateContext.IncomingMessage("bob", "text", "done", "我做完了", null, null))),
                marked::set);
        String reminder = TeamMailboxIngestor.ingest(agent, tc);
        assertNotNull(reminder, "AC16：有未读应产出 reminder");
        assertTrue(reminder.contains("<incoming-messages>"));
        assertTrue(reminder.contains("收到 2 条新消息"));
        assertTrue(reminder.contains("interface change"));
        assertEquals(List.of(0, 1), marked.get(), "读后批量 markRead");
        // reminder 已入 pendingReminders
        var taken = agent.runtime().takeReminders();
        assertEquals(1, taken.size());
        assertTrue(taken.get(0).contains("<incoming-messages>"));
    }

    @Test
    void 无未读返回null() {
        Agent agent = agent();
        TeammateContext tc = new TeammateContext("demo", "alice", "agent-a",
                () -> new TeammateContext.ReadUnreadView(List.of(), List.of()),
                i -> {});
        assertNull(TeamMailboxIngestor.ingest(agent, tc));
    }

    @Test
    void plan审批通过切回default_驳回带反馈() {
        Agent approved = Agent.builder(
                new com.cortex.llm.LlmClient() {
                    @Override
                    public java.util.concurrent.BlockingQueue<com.cortex.llm.StreamEvent> stream(com.cortex.llm.Request req) {
                        throw new UnsupportedOperationException();
                    }
                }, new com.cortex.tool.ToolRegistry(), "t",
                com.cortex.permission.PermissionEngine.create(tmp), SessionRuntime.empty(200000))
                .permissionMode(Mode.PLAN)
                .build();
        TeammateContext approve = new TeammateContext("demo", "alice", "agent-a",
                () -> new TeammateContext.ReadUnreadView(List.of(0), List.of(
                        new TeammateContext.IncomingMessage("lead", "plan_approval_response",
                                "approved", "", true, null))),
                i -> {});
        String r1 = TeamMailboxIngestor.ingest(approved, approve);
        assertTrue(r1.contains("已批准"), r1);
        // 模式切换经 runToCompletion 的 overrideMode 生效——这里直接验证 setPermissionMode 效果：
        // 通过再次 ingest 无从观察，改验证 runtime reminder 文案含切换提示
        assertTrue(approved.runtime().takeReminders().get(0).contains("切到 default"));

        Agent rejected = agent();
        TeammateContext feedback = new TeammateContext("demo", "alice", "agent-a",
                () -> new TeammateContext.ReadUnreadView(List.of(0), List.of(
                        new TeammateContext.IncomingMessage("lead", "plan_approval_response",
                                "rejected", "", false, "范围太大，先只做 A 模块"))),
                i -> {});
        String r2 = TeamMailboxIngestor.ingest(rejected, feedback);
        assertTrue(r2.contains("范围太大，先只做 A 模块"), "AC 驳回反馈透传");
    }
}
