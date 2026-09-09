package com.cortex.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * 单级笔记存储（F29/F30/F40）：在 {@code dir} 下管理 MEMORY.md 索引与 {@code <type>_<slug>.md} 笔记文件。
 * 文件写操作由 {@link ReentrantLock} 保护（N2）。
 */
public final class Store {

    private static final Logger LOG = Logger.getLogger(Store.class.getName());
    private static final String INDEX = "MEMORY.md";

    private final Path dir;
    private final ReentrantLock lock = new ReentrantLock();

    public Store(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    public void ensureDir() throws IOException {
        Files.createDirectories(dir);
    }

    /** 读取索引文件；不存在返回空字符串。 */
    public String loadIndex() throws IOException {
        Path idx = dir.resolve(INDEX);
        if (!Files.exists(idx)) {
            return "";
        }
        return Files.readString(idx);
    }

    /** 执行一组笔记操作（create/update/delete），更新文件与索引。 */
    public void apply(List<UpdateAction> actions) throws IOException {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            ensureDir();
            Path idx = dir.resolve(INDEX);
            List<String> indexLines = Files.exists(idx)
                    ? new ArrayList<>(Files.readAllLines(idx)) : new ArrayList<>();
            for (UpdateAction a : actions) {
                switch (a.action() == null ? "" : a.action()) {
                    case "create" -> createNote(a, indexLines);
                    case "update" -> updateNote(a, indexLines);
                    case "delete" -> deleteNote(a, indexLines);
                    default -> LOG.warning("未知记忆操作: " + a.action());
                }
            }
            Files.writeString(idx, String.join("\n", indexLines) + (indexLines.isEmpty() ? "" : "\n"));
        } finally {
            lock.unlock();
        }
    }

    private void createNote(UpdateAction a, List<String> indexLines) throws IOException {
        String type = a.type() == null ? "project_knowledge" : a.type();
        String title = a.title() == null ? "untitled" : a.title();
        String slug = a.slug() == null ? "note" : a.slug();
        String now = Instant.now().toString();
        String fileName = type + "_" + slug + ".md";
        Files.writeString(dir.resolve(fileName), renderNote(type, title, a.content(), now, now));
        indexLines.add("- [" + type + "] " + title + " — " + firstLine(a.content()));
    }

    private void updateNote(UpdateAction a, List<String> indexLines) throws IOException {
        String fileName = a.filename();
        if (fileName == null) {
            return;
        }
        Path file = dir.resolve(fileName);
        if (!Files.exists(file)) {
            return;
        }
        String now = Instant.now().toString();
        String content = a.content() == null ? "" : a.content();
        String created = readCreated(file);
        Files.writeString(file, renderNote(a.type(), a.title(), content, created, now));
        for (int i = 0; i < indexLines.size(); i++) {
            if (indexLines.get(i).contains(a.title() != null ? a.title() : "")) {
                indexLines.set(i, "- [" + (a.type() == null ? "project_knowledge" : a.type()) + "] "
                        + a.title() + " — " + firstLine(content));
                break;
            }
        }
    }

    private void deleteNote(UpdateAction a, List<String> indexLines) throws IOException {
        String fileName = a.filename();
        if (fileName == null) {
            return;
        }
        Files.deleteIfExists(dir.resolve(fileName));
        String title = a.title() == null ? "" : a.title();
        indexLines.removeIf(l -> title.isEmpty() ? false : l.contains(title));
    }

    private static String renderNote(String type, String title, String content, String created, String updated) {
        String c = content == null ? "" : content;
        return "---\n"
                + "type: " + type + "\n"
                + "title: " + (title == null ? "" : title) + "\n"
                + "created: " + created + "\n"
                + "updated: " + updated + "\n"
                + "---\n"
                + c + (c.isEmpty() || c.endsWith("\n") ? "" : "\n");
    }

    private static String readCreated(Path file) throws IOException {
        for (String line : Files.readAllLines(file)) {
            if (line.startsWith("created:")) {
                return line.substring("created:".length()).trim();
            }
        }
        return Instant.now().toString();
    }

    private static String firstLine(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String line = content.strip().split("\n", -1)[0];
        return line.length() > 60 ? line.substring(0, 60) + "…" : line;
    }
}
