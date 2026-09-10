package com.cortex.worktree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** SessionStore（T2/F30）：下划线小写字段、原子写、null 语义、坏文件。 */
class SessionStoreTest {

    @TempDir
    Path tmp;

    private WorktreeSession sample() {
        return WorktreeSession.create("/repo", "/repo/.cortex/worktrees/alice", "alice",
                "main", "abc123");
    }

    @Test
    void save_load往返_字段下划线小写() throws Exception {
        Path f = tmp.resolve("ws.json");
        SessionStore.save(f, sample());
        WorktreeSession back = SessionStore.load(f).orElseThrow();
        assertEquals("/repo", back.originalCwd());
        assertEquals("/repo/.cortex/worktrees/alice", back.worktreePath());
        assertEquals("alice", back.worktreeName());
        assertEquals("abc123", back.originalHeadCommit());
        assertEquals(36, back.sessionId().length());
        assertFalse(back.hookBased());
        // 磁盘上的字段名是小写下划线
        String json = Files.readString(f);
        assertTrue(json.contains("\"worktree_path\""));
        assertTrue(json.contains("\"original_cwd\""));
        assertTrue(json.contains("\"session_id\""));
    }

    @Test
    void save_null写入null字面量_clear等价() throws Exception {
        Path f = tmp.resolve("ws.json");
        SessionStore.save(f, sample());
        SessionStore.clear(f);
        assertEquals("null", Files.readString(f).strip());
        assertTrue(SessionStore.load(f).isEmpty());
    }

    @Test
    void 文件不存在或空白返回empty() throws Exception {
        assertTrue(SessionStore.load(tmp.resolve("nope.json")).isEmpty());
        Path f = tmp.resolve("blank.json");
        Files.writeString(f, "  ");
        assertTrue(SessionStore.load(f).isEmpty());
    }

    @Test
    void 坏JSON抛IOException() throws Exception {
        Path f = tmp.resolve("bad.json");
        Files.writeString(f, "{not json");
        assertThrows(java.io.IOException.class, () -> SessionStore.load(f));
    }

    @Test
    void 原子写不留tmp文件且可覆盖() throws Exception {
        Path f = tmp.resolve("ws.json");
        SessionStore.save(f, sample());
        SessionStore.save(f, WorktreeSession.create("/r2", "/r2/wt", "w2", "main", "def"));
        assertEquals("w2", SessionStore.load(f).orElseThrow().worktreeName());
        assertFalse(Files.exists(f.resolveSibling(f.getFileName() + ".tmp")),
                "原子 move 后 tmp 应消失");
    }
}
