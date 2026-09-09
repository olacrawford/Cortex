package com.cortex.task;

import com.cortex.agent.Agent;
import com.cortex.agent.SessionRuntime;
import com.cortex.conversation.ConversationManager;
import com.cortex.llm.LlmClient;
import com.cortex.llm.Request;
import com.cortex.llm.StreamEvent;
import com.cortex.permission.PermissionEngine;
import com.cortex.tool.Result;
import com.cortex.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 4 个 task 工具（F20/AC13/AC14/AC15）。 */
class ToolsTest {

    @TempDir
    Path root;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class FakeClient implements LlmClient {
        private final List<List<StreamEvent>> script;
        private int index = 0;

        FakeClient(List<List<StreamEvent>> script) { this.script = script; }

        @Override
        public LinkedBlockingQueue<StreamEvent> stream(Request req) {
            List<StreamEvent> events = index < script.size() ? script.get(index) : script.get(script.size() - 1);
            index++;
            return new LinkedBlockingQueue<>(events);
        }
    }

    /** 起一个已完成的后台任务，返回其 id。 */
    private String launchCompleted(Manager mgr, String name) throws Exception {
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.TextDelta("任务结果文本"),
                        new StreamEvent.StreamEnd("stop", 0, 0))));
        ConversationManager conv = new ConversationManager();
        Agent a = new Agent(client, new ToolRegistry(), "test", PermissionEngine.create(root),
                SessionRuntime.empty(200000));
        String id = mgr.launch(a, conv, name, "任务");
        assertEquals(id, mgr.doneQueue().poll(10, TimeUnit.SECONDS));
        return id;
    }

    @Test
    void taskList返回全部任务字段() throws Exception {
        Manager mgr = new Manager();
        launchCompleted(mgr, "w1");
        launchCompleted(mgr, null);

        Result r = new TaskListTool(mgr).execute("{}");
        assertFalse(r.isError());
        JsonNode arr = MAPPER.readTree(r.content());
        assertTrue(arr.isArray());
        assertEquals(2, arr.size());
        JsonNode first = arr.get(0);
        assertTrue(first.has("id"));
        assertTrue(first.has("name"));
        assertTrue(first.has("status"));
        assertTrue(first.has("tool_count"));
        assertTrue(first.has("last_activity"));
        assertEquals("completed", first.get("status").asText());
    }

    @Test
    void taskGet已知id返回完整状态_未知id返回错误() throws Exception {
        Manager mgr = new Manager();
        String id = launchCompleted(mgr, "w2");

        Result ok = new TaskGetTool(mgr).execute("{\"task_id\":\"" + id + "\"}");
        assertFalse(ok.isError());
        JsonNode node = MAPPER.readTree(ok.content());
        assertEquals(id, node.get("id").asText());
        assertEquals("completed", node.get("status").asText());
        assertEquals("任务结果文本", node.get("result").asText());
        assertTrue(node.has("start_time"));
        assertTrue(node.has("usage"));

        Result bad = new TaskGetTool(mgr).execute("{\"task_id\":\"nope\"}");
        assertTrue(bad.isError());
        assertTrue(bad.content().contains("未知 task_id"));
    }

    @Test
    void taskStop触发取消并返回请求JSON() throws Exception {
        Manager mgr = new Manager();
        String id = launchCompleted(mgr, null); // 已完成任务 stop 也返回成功（幂等触发）
        Result r = new TaskStopTool(mgr).execute("{\"task_id\":\"" + id + "\"}");
        assertFalse(r.isError());
        JsonNode node = MAPPER.readTree(r.content());
        assertEquals("cancellation_requested", node.get("status").asText());

        Result bad = new TaskStopTool(mgr).execute("{\"task_id\":\"ghost\"}");
        assertTrue(bad.isError());
    }

    @Test
    void sendMessage续派返回resumed() throws Exception {
        Manager mgr = new Manager();
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.TextDelta("第一轮"), new StreamEvent.StreamEnd("stop", 0, 0)),
                List.of(new StreamEvent.TextDelta("第二轮"), new StreamEvent.StreamEnd("stop", 0, 0))));
        ConversationManager conv = new ConversationManager();
        Agent a = new Agent(client, new ToolRegistry(), "test", PermissionEngine.create(root),
                SessionRuntime.empty(200000));
        mgr.launch(a, conv, "worker9", "任务");
        mgr.doneQueue().poll(10, TimeUnit.SECONDS);

        Result r = new SendMessageTool(mgr).execute("{\"name\":\"worker9\",\"message\":\"继续干\"}");
        assertFalse(r.isError());
        JsonNode node = MAPPER.readTree(r.content());
        assertEquals("resumed", node.get("status").asText());
        // 第二轮结果经 done 队列 + 任务状态可取
        assertEquals(node.get("task_id").asText(), mgr.doneQueue().poll(10, TimeUnit.SECONDS));
        assertEquals("第二轮", mgr.get(node.get("task_id").asText()).orElseThrow().result());

        Result bad = new SendMessageTool(mgr).execute("{\"name\":\"ghost\",\"message\":\"x\"}");
        assertTrue(bad.isError());
    }

    @Test
    void 四个工具的元信息() {
        Manager mgr = new Manager();
        assertEquals("TaskList", new TaskListTool(mgr).name());
        assertTrue(new TaskListTool(mgr).readOnly());
        assertEquals("TaskGet", new TaskGetTool(mgr).name());
        assertTrue(new TaskGetTool(mgr).readOnly());
        assertEquals("TaskStop", new TaskStopTool(mgr).name());
        assertFalse(new TaskStopTool(mgr).readOnly());
        assertEquals("SendMessage", new SendMessageTool(mgr).name());
        assertFalse(new SendMessageTool(mgr).readOnly());
    }
}
