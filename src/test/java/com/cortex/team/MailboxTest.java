package com.cortex.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/** Mailbox（T5/T6/F33/AC12/AC14/AC15）：write/read/markRead、并发、stale 锁。 */
class MailboxTest {

    @TempDir
    Path tmp;

    private Mailbox box() throws Exception {
        return new Mailbox(tmp.resolve("mailbox-" + System.nanoTime()));
    }

    private Message msg(String from, String summary, String content) {
        return new Message(from, "alice", MessageType.TEXT, summary, content, null, 0, false);
    }

    @Test
    void write_read_markRead往返() throws Exception {
        Mailbox m = box();
        m.write("agent-1", msg("lead", "hi", "hello"));
        m.write("agent-1", msg("bob", "yo", "world"));
        var unread = m.readUnread("agent-1");
        assertEquals(2, unread.messages().size());
        m.markRead("agent-1", unread.indices());
        assertTrue(m.readUnread("agent-1").isEmpty(), "标记后未读清空");
        assertEquals(2, m.read("agent-1").size(), "消息本体仍在");
    }

    @Test
    void 不同agentId邮箱隔离() throws Exception {
        Mailbox m = box();
        m.write("agent-1", msg("lead", "a", "a"));
        m.write("agent-2", msg("lead", "b", "b"));
        assertEquals(1, m.read("agent-1").size());
        assertEquals(1, m.read("agent-2").size());
    }

    @Test
    void 并发10线程写同一邮箱_无丢失() throws Exception {
        Mailbox m = box();
        int n = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int id = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    m.write("agent-1", msg("t" + id, "s" + id, "c" + id));
                } catch (Exception e) {
                    fail(e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(n, m.read("agent-1").size(), "AC14：10 条全部落盘无丢失");
    }

    @Test
    void stale锁超时可清除() throws Exception {
        Path dir = tmp.resolve("mailbox-stale");
        Mailbox m = new Mailbox(dir);
        Path lock = dir.resolve("agent-1.lock");
        Files.writeString(lock, "x");
        // 把 mtime 拨到 11 秒前（AC15：>10 秒视为 stale）
        var view = Files.getFileAttributeView(lock, java.nio.file.attribute.BasicFileAttributeView.class);
        view.setTimes(java.nio.file.attribute.FileTime.fromMillis(
                Instant.now().minus(Duration.ofSeconds(11)).toEpochMilli()), null, null);
        m.write("agent-1", msg("lead", "after-stale", "ok"));
        assertFalse(Files.exists(lock), "stale 锁应被清理");
        assertEquals(1, m.read("agent-1").size());
    }

    @Test
    void 文件锁互斥_序列获取() throws Exception {
        Path lock = tmp.resolve("s.lock");
        try (AutoCloseable l1 = FileLock.acquire(lock)) {
            assertThrows(IOException.class, () -> FileLock.acquire(lock).close(),
                    "他人持锁时应失败（除非 stale）");
        }
        assertDoesNotThrow(() -> FileLock.acquire(lock).close(), "释放后可再获取");
    }
}
