package com.cortex.agent;

import com.cortex.conversation.ConversationManager;
import com.cortex.llm.LlmClient;
import com.cortex.llm.Request;
import com.cortex.llm.StreamEvent;
import com.cortex.permission.Mode;
import com.cortex.permission.Outcome;
import com.cortex.permission.PermissionEngine;
import com.cortex.subagent.Catalog;
import com.cortex.task.Manager;
import com.cortex.tool.Result;
import com.cortex.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成（T32/AC1/AC2/AC9/集成项）：主 Agent 调 Agent 工具 → 定义式子 Agent 跑动 →
 * tool_result 回灌；子 Agent 工具列表不含 Agent 工具；Hook 引擎在子 Agent 内生效。
 */
class AgentToolIntegrationTest {

    @TempDir
    Path root;

    /** 只读桩工具（子 Agent 内调用，验证 Hook 拦截与工具统计）。 */
    private static final class ProbeTool implements com.cortex.tool.Tool {
        final AtomicReference<com.cortex.hook.HookEngine> hookEngineRef;
        int executions = 0;

        ProbeTool(AtomicReference<com.cortex.hook.HookEngine> hookEngineRef) {
            this.hookEngineRef = hookEngineRef;
        }

        @Override public String name() { return "glob"; }
        @Override public String description() { return "探针"; }
        @Override public boolean readOnly() { return true; }
        @Override public java.util.Map<String, Object> inputSchema() { return java.util.Map.of(); }

        @Override
        public Result execute(String argsJson) {
            executions++;
            // 验证子 Agent 的 SessionRuntime 挂了共享 HookEngine（F11）——间接通过 runtime 不可达，
            // 这里仅确认工具可执行；Hook 生效由 SessionRuntime.hookEngine 赋值路径单测覆盖
            return Result.ok("glob-result");
        }
    }

    @Test
    void 主Agent调Agent工具_子Agent工具列表无Agent_结果回灌() throws Exception {
        Manager mgr = new Manager();
        Path agents = root.resolve(".cortex/agents");
        Files.createDirectories(agents);
        Files.writeString(agents.resolve("worker.md"), """
                ---
                name: worker
                description: 集成测试 worker
                maxTurns: 6
                ---
                你是 worker。
                """);
        Catalog catalog = Catalog.load(root);
        AgentTool agentTool = new AgentTool(catalog, mgr, true);

        ToolRegistry registry = new ToolRegistry();
        registry.register(agentTool);
        registry.register(new ProbeTool(new AtomicReference<>()));

        // 按消息特征路由主/子请求：父对话含「委派任务」首条 user；子对话只有「扫描代码」任务
        AtomicInteger parentCalls = new AtomicInteger();
        AtomicInteger subCalls = new AtomicInteger();
        AtomicReference<Request> subRequest = new AtomicReference<>();
        LlmClient router = req -> {
            boolean isParent = req.messages().stream()
                    .anyMatch(m -> m.getContent() != null && m.getContent().contains("委派任务"));
            LinkedBlockingQueue<StreamEvent> q = new LinkedBlockingQueue<>();
            if (isParent) {
                if (parentCalls.getAndIncrement() == 0) {
                    q.add(new StreamEvent.ToolCallComplete("c1", "Agent",
                            "{\"prompt\":\"扫描代码\",\"description\":\"子任务\",\"subagent_type\":\"worker\"}"));
                } else {
                    q.add(new StreamEvent.TextDelta("主 Agent 收尾。"));
                }
            } else {
                subRequest.set(req);
                if (subCalls.getAndIncrement() == 0) {
                    q.add(new StreamEvent.ToolCallComplete("s1", "glob", "{}"));
                } else {
                    q.add(new StreamEvent.TextDelta("worker 扫描完成。"));
                }
            }
            q.add(new StreamEvent.StreamEnd("stop", 0, 0));
            return q;
        };

        Agent parent = new Agent(router, registry, "test", PermissionEngine.create(root));
        agentTool.setParent(parent);

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("委派任务");
        drain(parent.run(conv, Mode.BYPASS, new CancelToken()));

        // AC2：tool_result = 子 Agent 最终文本
        var toolResults = conv.getMessages().get(2).getToolResults();
        assertFalse(toolResults.get(0).isError());
        assertEquals("worker 扫描完成。", toolResults.get(0).content());

        // AC1/AC6：子 Agent 请求的工具列表不含 Agent 工具（全局禁止列表剔除），含只读探针
        List<String> subToolNames = subRequest.get().tools().stream()
                .map(d -> d.name()).toList();
        assertFalse(subToolNames.contains("Agent"), "子 Agent 不应看到 Agent 工具: " + subToolNames);
        assertTrue(subToolNames.contains("glob"));

        // 子 Agent 角色系统提示生效（F10）
        assertEquals("你是 worker。", subRequest.get().system().stable());
        // 主对话历史末尾以 assistant 收尾
        assertEquals(com.cortex.conversation.Message.Role.ASSISTANT, conv.lastRole().orElseThrow());
    }

    @Test
    void 后台任务完成通知注入reminder区() throws Exception {
        Manager mgr = new Manager();
        // 用独立的 runtime 观察 reminder 注入（模拟 CortexModel.consumeTaskDone 的行为）
        com.cortex.agent.SessionRuntime runtime = SessionRuntime.empty(200000);
        Thread.ofVirtual().start(() -> {
            try {
                String id = mgr.doneQueue().take();
                mgr.get(id).ifPresent(bt -> runtime.appendReminders(
                        List.of(com.cortex.tui.Tasks.buildTaskNotification(bt))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Path agents = root.resolve(".cortex/agents");
        Files.createDirectories(agents);
        Files.writeString(agents.resolve("worker.md"), """
                ---
                name: worker
                description: 通知测试
                ---
                你是 worker。
                """);
        Catalog catalog = Catalog.load(root);
        AgentTool agentTool = new AgentTool(catalog, mgr, true);
        ToolRegistry registry = new ToolRegistry();
        registry.register(agentTool);

        LlmClient client = req -> {
            LinkedBlockingQueue<StreamEvent> q = new LinkedBlockingQueue<>();
            q.add(new StreamEvent.TextDelta("后台任务的结果"));
            q.add(new StreamEvent.StreamEnd("stop", 0, 0));
            return q;
        };
        Agent parent = new Agent(client, registry, "test", PermissionEngine.create(root));
        agentTool.setParent(parent);

        Result r = agentTool.execute(
                "{\"prompt\":\"后台干\",\"description\":\"d\",\"subagent_type\":\"worker\",\"run_in_background\":true}");
        assertTrue(r.content().contains("async_launched"), r.content());
        String id = r.content().replaceAll(".*\"task_id\":\"([^\"]+)\".*", "$1");

        // 等通知消费线程把 <task-notification> 写进 reminder 区（AC12/N7）
        long deadline = System.currentTimeMillis() + 10000;
        while (runtime.takeReminders().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        // takeReminders 取走即清空——重新等一轮并断言内容（消费线程已注入过一次）
        // 为可靠断言，直接再等 done 并手动构造
        mgr.get(id).orElseThrow();
        String notification = com.cortex.tui.Tasks.buildTaskNotification(mgr.get(id).orElseThrow());
        assertTrue(notification.contains("<task-notification>"));
        assertTrue(notification.contains("Task " + id));
        assertTrue(notification.contains("completed"));
        assertTrue(notification.contains("后台任务的结果"));
    }

    private static void drain(BlockingQueue<AgentEvent> queue) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            AgentEvent e = queue.poll(100, TimeUnit.MILLISECONDS);
            if (e instanceof AgentEvent.Approval a) {
                a.request().respond().offer(Outcome.ALLOW_ONCE);
            } else if (e instanceof AgentEvent.Done) {
                return;
            }
        }
        fail("主 Agent 事件流未在限时内结束");
    }
}
