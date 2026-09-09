package com.cortex.memory;

import com.cortex.conversation.Message;
import com.cortex.llm.LlmClient;
import com.cortex.llm.Request;
import com.cortex.llm.StreamEvent;
import com.cortex.llm.SystemPrompt;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * 记忆管理器（F32~F42）：合并两级索引注入上下文（截断到 25KB），
 * 在 Agent 回合结束后异步发起记忆更新，解析 JSON 操作并分发到两级 Store。
 * 更新在独立 virtual thread 执行、不阻塞主会话，失败静默记录日志。
 */
public final class Manager {

    private static final Logger LOG = Logger.getLogger(Manager.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int INDEX_MAX_BYTES = 25 * 1024;

    private final Store projectStore;
    private final Store userStore;
    private final ReentrantLock updateLock = new ReentrantLock();
    private volatile LlmClient client;
    private volatile String model;

    public Manager(Path projectRoot, Path userHome, LlmClient client, String model) {
        this.projectStore = new Store(projectRoot.resolve(".cortex").resolve("memory"));
        this.userStore = new Store(userHome.resolve(".cortex").resolve("memory"));
        this.client = client;
        this.model = model;
    }

    /** 延迟设置 provider（启动时 provider 可能尚未选定）。 */
    public void setProvider(LlmClient client, String model) {
        this.client = client;
        this.model = model;
    }

    /** 合并两级索引（项目级在前、用户级在后），超过 25KB 截断并附 truncated 标注。 */
    public String loadIndex() throws IOException {
        String p = projectStore.loadIndex();
        String u = userStore.loadIndex();
        String combined = p.isBlank() ? u : (u.isBlank() ? p : p + "\n\n" + u);
        byte[] bytes = combined.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > INDEX_MAX_BYTES) {
            String cut = new String(bytes, 0, INDEX_MAX_BYTES, StandardCharsets.UTF_8);
            return cut + "\n(index truncated)";
        }
        return combined;
    }

    /** 异步发起记忆更新：独立 virtual thread，不阻塞用户下一次输入。 */
    public void updateAsync(List<Message> recentMsgs) {
        Thread.ofVirtual().name("memory-update").start(() -> {
            try {
                doUpdate(recentMsgs);
            } catch (Exception e) {
                LOG.warning("记忆更新失败: " + e.getMessage());
            }
        });
    }

    private void doUpdate(List<Message> recentMsgs) throws Exception {
        updateLock.lock();
        try {
            LlmClient c = this.client;
            if (c == null || recentMsgs == null || recentMsgs.isEmpty()) {
                return;
            }
            String index = loadIndex();
            List<Message> msgs = new ArrayList<>(recentMsgs);
            msgs.add(new Message(Message.Role.USER, "现有记忆索引:\n" + index));
            Request req = new Request(msgs, List.of(), new SystemPrompt(PromptTemplates.system(), ""), "");
            String reply = collect(c.stream(req));
            if (reply == null || reply.isBlank()) {
                return;
            }
            List<UpdateAction> actions = MAPPER.readValue(reply, new TypeReference<List<UpdateAction>>() {});
            for (UpdateAction a : actions) {
                if ("user".equals(a.level())) {
                    userStore.apply(List.of(a));
                } else {
                    projectStore.apply(List.of(a));
                }
            }
        } finally {
            updateLock.unlock();
        }
    }

    private static String collect(BlockingQueue<StreamEvent> queue) throws IOException {
        StringBuilder sb = new StringBuilder();
        try {
            while (true) {
                StreamEvent ev = queue.poll(200, TimeUnit.MILLISECONDS);
                if (ev == null) {
                    continue;
                }
                switch (ev) {
                    case StreamEvent.TextDelta d -> sb.append(d.text());
                    case StreamEvent.StreamEnd s -> {
                        return sb.toString();
                    }
                    case StreamEvent.Error e -> throw new IOException(e.message());
                    default -> {
                        // ThinkingDelta / ToolCallComplete / UsageEvent：丢弃
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
