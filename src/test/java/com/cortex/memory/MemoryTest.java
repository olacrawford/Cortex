package com.cortex.memory;

import com.cortex.conversation.Message;
import com.cortex.llm.LlmClient;
import com.cortex.llm.Request;
import com.cortex.llm.StreamEvent;
import com.cortex.llm.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryTest {

    @TempDir
    Path tempDir;

    private LlmClient mock(String reply) {
        return req -> {
            BlockingQueue<StreamEvent> q = new LinkedBlockingQueue<>();
            q.add(new StreamEvent.TextDelta(reply));
            q.add(new StreamEvent.StreamEnd("stop", 0, 0));
            return q;
        };
    }

    @Test
    void storeCreateNote() throws Exception {
        Store store = new Store(tempDir);
        store.apply(List.of(new UpdateAction("create", "project", "project_knowledge",
                "API 约定", "api_conventions", null, "用 camelCase 命名 API。")));
        assertTrue(Files.exists(tempDir.resolve("project_knowledge_api_conventions.md")));
        String content = Files.readString(tempDir.resolve("project_knowledge_api_conventions.md"));
        assertTrue(content.contains("type: project_knowledge"));
        assertTrue(content.contains("title: API 约定"));
        String index = Files.readString(tempDir.resolve("MEMORY.md"));
        assertTrue(index.contains("[project_knowledge] API 约定"));
    }

    @Test
    void storeUpdateNote() throws Exception {
        Store store = new Store(tempDir);
        store.apply(List.of(new UpdateAction("create", "project", "project_knowledge",
                "约定", "conv", null, "旧内容")));
        store.apply(List.of(new UpdateAction("update", "project", null, "约定",
                null, "project_knowledge_conv.md", "新内容")));
        String content = Files.readString(tempDir.resolve("project_knowledge_conv.md"));
        assertTrue(content.contains("新内容"));
        String index = Files.readString(tempDir.resolve("MEMORY.md"));
        assertTrue(index.contains("新内容"));
    }

    @Test
    void storeDeleteNote() throws Exception {
        Store store = new Store(tempDir);
        store.apply(List.of(new UpdateAction("create", "project", "reference_material",
                "资料", "mat", null, "内容")));
        store.apply(List.of(new UpdateAction("delete", "project", null, "资料",
                null, "reference_material_mat.md", null)));
        assertFalse(Files.exists(tempDir.resolve("reference_material_mat.md")));
        String index = Files.readString(tempDir.resolve("MEMORY.md"));
        assertFalse(index.contains("资料"));
    }

    @Test
    void managerLoadIndex() throws Exception {
        Path proj = tempDir.resolve("proj");
        Path user = tempDir.resolve("user");
        Files.createDirectories(proj.resolve(".cortex/memory"));
        Files.createDirectories(user.resolve(".cortex/memory"));
        Files.writeString(proj.resolve(".cortex/memory/MEMORY.md"), "- [project_knowledge] P\n");
        Files.writeString(user.resolve(".cortex/memory/MEMORY.md"), "- [user_preference] U\n");
        Manager m = new Manager(proj, user, mock("[]"), "m");
        String idx = m.loadIndex();
        assertTrue(idx.indexOf("P") < idx.indexOf("U"));
    }

    @Test
    void managerLoadIndexTruncate() throws Exception {
        Path proj = tempDir.resolve("proj");
        Path user = tempDir.resolve("user");
        Files.createDirectories(proj.resolve(".cortex/memory"));
        Files.createDirectories(user.resolve(".cortex/memory"));
        Files.writeString(proj.resolve(".cortex/memory/MEMORY.md"), "- [project_knowledge] " + "x".repeat(26000) + "\n");
        Manager m = new Manager(proj, user, mock("[]"), "m");
        String idx = m.loadIndex();
        assertTrue(idx.contains("(index truncated)"));
        assertTrue(idx.getBytes().length <= 25 * 1024 + 32);
    }

    @Test
    void managerUpdateAsyncParsesResponse() throws Exception {
        Path proj = tempDir.resolve("proj");
        Path user = tempDir.resolve("user");
        Files.createDirectories(proj.resolve(".cortex/memory"));
        Files.createDirectories(user.resolve(".cortex/memory"));
        String reply = "[{\"action\":\"create\",\"level\":\"project\",\"type\":\"project_knowledge\","
                + "\"title\":\"约定\",\"slug\":\"conv\",\"content\":\"用 camelCase\"}]";
        Manager m = new Manager(proj, user, mock(reply), "m");
        m.updateAsync(List.of(new Message(Message.Role.USER, "记住：API 用 camelCase")));
        awaitFile(proj.resolve(".cortex/memory/project_knowledge_conv.md"));
        assertTrue(Files.exists(proj.resolve(".cortex/memory/project_knowledge_conv.md")));
    }

    private static void awaitFile(Path p) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (Files.exists(p)) {
                return;
            }
            Thread.sleep(50);
        }
    }
}
