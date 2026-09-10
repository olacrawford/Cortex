package com.cortex.team;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 成员邮箱（F32/F33）：每个收件人一个 {@code <agentId>.json}（消息数组），
 * 写入经 {@link FileLock} 串行 + 原子替换；并发安全由文件锁保证（N3）。
 */
public final class Mailbox {

    private final Path dir;

    public Mailbox(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
    }

    public Path dir() {
        return dir;
    }

    /** 追加一条消息（timestamp==0 时自动取当前 epoch 秒）。 */
    public void write(String agentId, Message msg) throws IOException {
        Path lockPath = dir.resolve(agentId + ".lock");
        try (AutoCloseable lock = FileLock.acquire(lockPath)) {
            List<Message> messages = new ArrayList<>(readLocked(agentId));
            long ts = msg.timestamp() == 0 ? Instant.now().getEpochSecond() : msg.timestamp();
            messages.add(msg.withTimestamp(ts));
            Persistence.atomicWriteJson(dir.resolve(agentId + ".json"), new Box(List.copyOf(messages)));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("邮箱写入失败: " + agentId, e);
        }
    }

    /** 全部消息。 */
    public List<Message> read(String agentId) throws IOException {
        Path lockPath = dir.resolve(agentId + ".lock");
        try (AutoCloseable lock = FileLock.acquire(lockPath)) {
            return readLocked(agentId);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("邮箱读取失败: " + agentId, e);
        }
    }

    /** 未读消息及其下标（F33）。 */
    public ReadUnreadResult readUnread(String agentId) throws IOException {
        Path lockPath = dir.resolve(agentId + ".lock");
        try (AutoCloseable lock = FileLock.acquire(lockPath)) {
            List<Message> all = readLocked(agentId);
            List<Integer> indices = new ArrayList<>();
            List<Message> unread = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                if (!all.get(i).read()) {
                    indices.add(i);
                    unread.add(all.get(i));
                }
            }
            return new ReadUnreadResult(indices, unread);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("邮箱读取失败: " + agentId, e);
        }
    }

    /** 按下标批量标记已读（F41 读后调用）。 */
    public void markRead(String agentId, List<Integer> indices) throws IOException {
        if (indices == null || indices.isEmpty()) {
            return;
        }
        Path lockPath = dir.resolve(agentId + ".lock");
        try (AutoCloseable lock = FileLock.acquire(lockPath)) {
            List<Message> all = new ArrayList<>(readLocked(agentId));
            List<Message> updated = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                Message m = all.get(i);
                updated.add(indices.contains(i) ? m.withRead(true) : m);
            }
            Persistence.atomicWriteJson(dir.resolve(agentId + ".json"), new Box(updated));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("邮箱标记失败: " + agentId, e);
        }
    }

    /** 邮箱目录是否还存在（Team 删除检测，F19a 优雅退出用）。 */
    public boolean exists() {
        return Files.isDirectory(dir);
    }

    private List<Message> readLocked(String agentId) throws IOException {
        Path file = dir.resolve(agentId + ".json");
        if (!Files.exists(file)) {
            return List.of();
        }
        Optional<Box> box = Persistence.readJson(file, Box.class);
        return box.map(Box::messages).orElse(List.of());
    }

    /** 邮箱文件结构。 */
    public record Box(List<Message> messages) {
        public Box {
            if (messages == null) {
                messages = new ArrayList<>();
            }
        }
    }

    /** 未读读取结果（T6）：indices 与 messages 一一对应。 */
    public record ReadUnreadResult(List<Integer> indices, List<Message> messages) {
        public ReadUnreadResult {
            indices = indices == null ? List.of() : indices;
            messages = messages == null ? List.of() : messages;
        }

        public boolean isEmpty() {
            return messages.isEmpty();
        }
    }
}
