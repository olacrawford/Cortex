package com.cortex.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 共享任务列表（T8/F26-F30/AC10/AC11）：CRUD + 依赖双向维护 + isReady。 */
class TaskStoreTest {

    @TempDir
    Path tmp;

    private TaskStore store() {
        return new TaskStore(tmp.resolve("tasks-" + System.nanoTime() + ".json"));
    }

    @Test
    void create_get_未知id() throws Exception {
        TaskStore s = store();
        String id = s.create("重构模块A", "细节", "alice", List.of());
        assertTrue(id.matches("task_[0-9a-f]{6}"));
        assertTrue(s.get(id).isPresent());
        assertEquals("重构模块A", s.get(id).orElseThrow().title());
        assertTrue(s.get("task_nope").isEmpty());
    }

    @Test
    void update_状态与指派() throws Exception {
        TaskStore s = store();
        String id = s.create("t", "", "", List.of());
        s.update(id, new TaskPatch(null, null, "in_progress", "bob", null, null, null, null));
        assertEquals("in_progress", s.get(id).orElseThrow().status());
        assertEquals("bob", s.get(id).orElseThrow().assignee());
    }

    @Test
    void addBlockedBy双向维护() throws Exception {
        TaskStore s = store();
        String a = s.create("A", "", "", List.of());
        String b = s.create("B", "", "", List.of());
        s.update(b, new TaskPatch(null, null, null, null, null, List.of(a), null, null)); // B blockedBy A
        assertEquals(List.of(a), s.get(b).orElseThrow().blockedBy(), "AC10：B.blockedBy 含 A");
        assertEquals(List.of(b), s.get(a).orElseThrow().blocks(), "AC10：A.blocks 双向含 B");
    }

    @Test
    void isReady反映阻塞是否全部完成() throws Exception {
        TaskStore s = store();
        String a = s.create("A", "", "", List.of());
        String b = s.create("B", "", "", List.of(a));
        // A 未完成 → B 未 ready
        assertFalse(s.list(null).stream().filter(v -> v.task().id().equals(b))
                .findFirst().orElseThrow().isReady(), "AC11");
        // A 完成 → B ready
        s.update(a, new TaskPatch(null, null, "completed", null, null, null, null, null));
        assertTrue(s.list(null).stream().filter(v -> v.task().id().equals(b))
                .findFirst().orElseThrow().isReady(), "AC11");
    }

    @Test
    void list按status过滤() throws Exception {
        TaskStore s = store();
        s.create("x", "", "", List.of());
        String y = s.create("y", "", "", List.of());
        s.update(y, new TaskPatch(null, null, "completed", null, null, null, null, null));
        assertEquals(1, s.list("pending").size());
        assertEquals(1, s.list("completed").size());
        assertEquals(2, s.list(null).size());
    }

    @Test
    void 持久化往返() throws Exception {
        Path f = tmp.resolve("p.json");
        TaskStore s = new TaskStore(f);
        String id = s.create("标题", "", "", List.of());
        assertTrue(Files.exists(f));
        TaskStore s2 = new TaskStore(f); // 模拟重启
        assertTrue(s2.get(id).isPresent());
    }
}
