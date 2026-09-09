package com.cortex.session;

import com.cortex.conversation.Message;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * session JSONL 追加写入器（F14~F16）：只追加不重写，崩溃最多丢最后一行；
 * append 加锁保证多线程原子，每次 flush + force(true) 刷盘；实现 {@link Closeable}。
 */
public final class Writer implements Closeable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FILE_NAME = "conversation.jsonl";

    private final ReentrantLock lock = new ReentrantLock();
    private final Path path;
    private final BufferedWriter writer;
    private final FileChannel channel;

    private boolean wroteFirst;
    private String model;

    private Writer(Path path, BufferedWriter writer, FileChannel channel) {
        this.path = path;
        this.writer = writer;
        this.channel = channel;
    }

    /** 设置首条消息携带的模型标签（provider 选定后调用）。 */
    public void setModel(String model) {
        this.model = model;
    }

    /** 当前会话存档 JSONL 的绝对路径（/session 用）。 */
    public Path path() {
        return path;
    }

    /** Conversation 的 onAppend 回调：每条消息追加落盘，首条携带 model。 */
    public void onAppend(com.cortex.conversation.Message msg) {
        try {
            boolean first = !wroteFirst;
            append(msg, model, first);
            wroteFirst = true;
        } catch (IOException e) {
            // JSONL 写入失败不中断对话（N5）
        }
    }

    /** Conversation 的 onReplace 回调：先写 compact 标记，再逐条追加新历史。 */
    public void onReplace(java.util.List<com.cortex.conversation.Message> msgs) {
        try {
            writeCompactMarker();
            appendAll(msgs);
        } catch (IOException e) {
            // 同上，静默
        }
    }

    /** 新建会话：创建目录并追加打开。 */
    public static Writer create(Path sessionDir) throws IOException {
        Files.createDirectories(sessionDir);
        return open(sessionDir);
    }

    /** 打开已有会话（/resume 用）：不创建目录，直接追加打开。 */
    public static Writer open(Path sessionDir) throws IOException {
        Path path = sessionDir.resolve(FILE_NAME);
        FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                StandardOpenOption.WRITE);
        return new Writer(path, new BufferedWriter(Channels.newWriter(ch, StandardCharsets.UTF_8)), ch);
    }

    /** 追加一条消息；isFirst 时携带 model（仅首条），供会话列表展示。 */
    public void append(Message msg, String model, boolean isFirst) throws IOException {
        Entry e = new Entry(null, roleWire(msg.getRole()), msg.getContent(),
                msg.getToolCalls().isEmpty() ? null : msg.getToolCalls(),
                msg.getToolResults().isEmpty() ? null : msg.getToolResults(),
                System.currentTimeMillis() / 1000,
                (isFirst && model != null) ? model : null);
        writeEntry(e);
    }

    /** 压缩标记行：在 replaceMessages 整体替换前写入。 */
    public void writeCompactMarker() throws IOException {
        writeEntry(Entry.compactMarker(System.currentTimeMillis() / 1000));
    }

    /** 逐条追加一批消息（压缩后新历史）。 */
    public void appendAll(List<Message> msgs) throws IOException {
        for (Message m : msgs) {
            append(m, null, false);
        }
    }

    private void writeEntry(Entry e) throws IOException {
        lock.lock();
        try {
            writer.write(MAPPER.writeValueAsString(e));
            writer.write("\n");
            writer.flush();
            channel.force(true);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            writer.close();
        } finally {
            lock.unlock();
        }
    }

    private static String roleWire(Message.Role role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }
}
