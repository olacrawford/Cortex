package com.cortex.task;

import com.cortex.agent.Agent;
import com.cortex.agent.AgentEvent;
import com.cortex.agent.CancelToken;
import com.cortex.agent.SessionRuntime;
import com.cortex.conversation.ConversationManager;
import com.cortex.llm.LlmClient;
import com.cortex.llm.Request;
import com.cortex.llm.StreamEvent;
import com.cortex.permission.PermissionEngine;
import com.cortex.tool.Result;
import com.cortex.tool.Tool;
import com.cortex.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Manager 全生命周期（F14-F18/N3）：launch、FAILED 隔离、stop、sendMessage、byName 覆盖。 */
class ManagerTest {

    @TempDir
    Path root;

    private static final class FakeClient implements LlmClient {
        private final List<List<StreamEvent>> script = new ArrayList<>();
        private List<StreamEvent> defaultResponse = List.of(new StreamEvent.StreamEnd("stop", 0, 0));
        private int index = 0;

        void enqueue(List<StreamEvent> events) { script.add(events); }
        void setDefault(List<StreamEvent> events) { defaultResponse = events; }

        @Override
        public LinkedBlockingQueue<StreamEvent> stream(Request req) {
            List<StreamEvent> events = index < script.size() ? script.get(index) : defaultResponse;
            index++;
            return new LinkedBlockingQueue<>(events);
        }
    }

    /** 阻塞桩工具：latch 释放前一直占着执行（长跑模拟）。 */
    private static final class BlockingTool implements Tool {
        final CountDownLatch release = new CountDownLatch(1);

        @Override public String name() { return "blocking"; }
        @Override public String description() { return "阻塞桩"; }
        @Override public boolean readOnly() { return false; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }

        @Override
        public Result execute(String argsJson) {
            try {
                // 不响应中断：模拟子 Agent 卡在长工具里，取消靠循环检查点退出
                release.await();
                return Result.ok("done");
            } catch (InterruptedException e) {
                return Result.ok("done");
            }
        }
    }

    private Agent subAgent(FakeClient client, ToolRegistry registry, ConversationManager conv) {
        Agent a = new Agent(client, registry, "test", PermissionEngine.create(root), SessionRuntime.empty(200000));
        return a;
    }

    private BackgroundTask launchAndWaitDone(Manager mgr, Agent agent, ConversationManager conv,
                                             LinkedBlockingQueue<String> done) throws Exception {
        String id = mgr.launch(agent, conv, null, "任务");
        String doneId = done.poll(10, TimeUnit.SECONDS);
        assertEquals(id, doneId);
        return mgr.get(id).orElseThrow();
    }

    @Test
    void launch跑完写COMPLETED并推送done() throws Exception {
        Manager mgr = new Manager();
        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.TextDelta("后台任务完成。"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        ConversationManager conv = new ConversationManager();

        BackgroundTask bt = launchAndWaitDone(mgr, subAgent(client, new ToolRegistry(), conv), conv, mgr.doneQueue());
        assertEquals(Status.COMPLETED, bt.status());
        assertEquals("后台任务完成。", bt.result());
        assertNotNull(bt.endTime());
    }

    @Test
    void 崩溃转FAILED_主程序不崩() throws Exception {
        Manager mgr = new Manager();
        FakeClient client = new FakeClient();
        // 第 1 轮流错误（非取消）→ runToCompletion 抛 RuntimeException
        client.enqueue(List.of(new StreamEvent.Error("爆炸")));
        ConversationManager conv = new ConversationManager();

        BackgroundTask bt = launchAndWaitDone(mgr, subAgent(client, new ToolRegistry(), conv), conv, mgr.doneQueue());
        assertEquals(Status.FAILED, bt.status());
        assertNotNull(bt.error());
        assertTrue(bt.errorMessage().contains("子 Agent"));
    }

    @Test
    void stop触发CANCELLED() throws Exception {
        Manager mgr = new Manager();
        BlockingTool blocking = new BlockingTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(blocking);

        FakeClient client = new FakeClient();
        client.setDefault(List.of(
                new StreamEvent.ToolCallComplete("c", "blocking", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        ConversationManager conv = new ConversationManager();

        String id = mgr.launch(subAgent(client, registry, conv), conv, "worker", "长任务");
        // 等任务真正跑起来（工具开始执行）
        BackgroundTask bt = mgr.get(id).orElseThrow();
        long deadline = System.currentTimeMillis() + 5000;
        while (bt.toolCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(mgr.stop(id));
        String doneId = mgr.doneQueue().poll(10, TimeUnit.SECONDS);
        assertEquals(id, doneId);
        assertEquals(Status.CANCELLED, bt.status());
    }

    @Test
    void stop未知id返回false() {
        Manager mgr = new Manager();
        assertFalse(mgr.stop("task_nope"));
        assertTrue(mgr.get("task_nope").isEmpty());
    }

    @Test
    void sendMessage给已完成的任务续派() throws Exception {
        Manager mgr = new Manager();
        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.TextDelta("第一轮结果"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        client.enqueue(List.of(
                new StreamEvent.TextDelta("第二轮结果"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        ConversationManager conv = new ConversationManager();

        String id = mgr.launch(subAgent(client, new ToolRegistry(), conv), conv, "worker1", "任务一");
        assertEquals(id, mgr.doneQueue().poll(10, TimeUnit.SECONDS));
        assertEquals(Status.COMPLETED, mgr.get(id).orElseThrow().status());

        String id2 = mgr.sendMessage("worker1", "再来一件事");
        assertEquals(id, id2, "同 id 复用（T21）");
        assertEquals(id, mgr.doneQueue().poll(10, TimeUnit.SECONDS));
        BackgroundTask bt = mgr.get(id).orElseThrow();
        assertEquals(Status.COMPLETED, bt.status());
        assertEquals("第二轮结果", bt.result());
        // 新 user 消息已追加
        assertEquals(4, conv.size()); // user(任务一) → assistant → user(再来一件事) → assistant
    }

    @Test
    void sendMessage对未完成任务报错() throws Exception {
        Manager mgr = new Manager();
        BlockingTool blocking = new BlockingTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(blocking);

        FakeClient client = new FakeClient();
        client.setDefault(List.of(
                new StreamEvent.ToolCallComplete("c", "blocking", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        ConversationManager conv = new ConversationManager();

        String id = mgr.launch(subAgent(client, registry, conv), conv, "busy", "长任务");
        BackgroundTask bt = mgr.get(id).orElseThrow();
        long deadline = System.currentTimeMillis() + 5000;
        while (bt.toolCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> mgr.sendMessage("busy", "续派"));
        assertTrue(e.getMessage().contains("尚未完成"));
        assertTrue(mgr.stop(id));
        assertThrows(IllegalStateException.class, () -> mgr.sendMessage("不存在", "x"));
    }

    @Test
    void byName后启动覆盖前() throws Exception {
        Manager mgr = new Manager();
        ConversationManager conv1 = new ConversationManager();
        ConversationManager conv2 = new ConversationManager();
        FakeClient c1 = new FakeClient();
        c1.enqueue(List.of(new StreamEvent.TextDelta("一"), new StreamEvent.StreamEnd("stop", 0, 0)));
        FakeClient c2 = new FakeClient();
        c2.enqueue(List.of(new StreamEvent.TextDelta("二"), new StreamEvent.StreamEnd("stop", 0, 0)));

        mgr.launch(subAgent(c1, new ToolRegistry(), conv1), conv1, "same", "任务一");
        String id2 = mgr.launch(subAgent(c2, new ToolRegistry(), conv2), conv2, "same", "任务二");
        // 等两个都完成
        mgr.doneQueue().poll(10, TimeUnit.SECONDS);
        mgr.doneQueue().poll(10, TimeUnit.SECONDS);
        // byName 指向后启动的
        assertEquals(id2, mgr.sendMessage("same", "续"));
        assertTrue(conv2.size() >= 3, "续派写进后启动任务的对话");
    }

    @Test
    void list按启动时间升序_事件聚合toolCount() throws Exception {
        Manager mgr = new Manager();
        StubCounter counter = new StubCounter();
        ToolRegistry registry = new ToolRegistry();
        registry.register(counter);
        FakeClient client = new FakeClient();
        client.enqueue(List.of(
                new StreamEvent.ToolCallComplete("c1", "counter", "{}"),
                new StreamEvent.StreamEnd("tool_use", 0, 0)));
        client.enqueue(List.of(
                new StreamEvent.TextDelta("ok"),
                new StreamEvent.StreamEnd("stop", 0, 0)));
        ConversationManager conv = new ConversationManager();

        String id = mgr.launch(subAgent(client, registry, conv), conv, null, "t");
        mgr.doneQueue().poll(10, TimeUnit.SECONDS);
        BackgroundTask bt = mgr.get(id).orElseThrow();
        // 聚合是异步的，给一点时间
        long deadline = System.currentTimeMillis() + 3000;
        while (bt.toolCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(1, bt.toolCount());
        assertEquals("counter", bt.lastActivity());
        assertEquals(1, mgr.list().size());
    }

    private static final class StubCounter implements Tool {
        @Override public String name() { return "counter"; }
        @Override public String description() { return "计数桩"; }
        @Override public boolean readOnly() { return true; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }
        @Override public Result execute(String argsJson) { return Result.ok("counted"); }
    }
}
