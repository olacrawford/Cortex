package com.cortex.compact;

import com.cortex.llm.ToolDef;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 压缩后的「恢复三段」：最近读过的文件快照、当前可用工具列表、边界提示消息。
 * <p>
 * 调用方必须在 runSummary 入口一次性拍好 {@link RecoveryState#snapshot()} 快照，
 * 把快照而非 RecoveryState 传入 {@link #buildRecoveryAttachment}，避免恢复段渲染期间
 * 另一个线程通过 {@code recordFile} 改变状态导致漂移。
 */
public final class Recovery {

    /** 边界提示消息的固定文案。 */
    public static final String BOUNDARY_NOTICE =
            "需要文件原文、错误原文、用户原话时，请使用文件读取工具重新读取对应路径，不要依据摘要内容做猜测。";

    private Recovery() {}

    /** 一次成功文件读取的记录：绝对路径 + 纯净字节（不带行号前缀）+ 读取时间。 */
    public record FileReadRecord(String path, String content, Instant timestamp) {}

    /**
     * Agent 主循环写、compact 摘要时读的文件追踪状态。
     * 并发安全：所有读写都在锁内完成（AC23b）；files 的键是文件绝对路径。
     */
    public static final class RecoveryState {
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<String, FileReadRecord> files = new HashMap<>();

        /** 记录一次文件读取；非绝对路径先归一化为绝对路径再存。 */
        public void recordFile(String path, String content) {
            String abs = Path.of(path).toAbsolutePath().normalize().toString();
            FileReadRecord rec = new FileReadRecord(abs, content, Instant.now());
            lock.lock();
            try {
                files.put(abs, rec);
            } finally {
                lock.unlock();
            }
        }

        /** 按时间戳倒序返回一份不可变快照（拷贝，不暴露内部 map）。 */
        public List<FileReadRecord> snapshot() {
            lock.lock();
            try {
                List<FileReadRecord> list = new ArrayList<>(files.values());
                list.sort(Comparator.comparing(FileReadRecord::timestamp).reversed());
                return List.copyOf(list);
            } finally {
                lock.unlock();
            }
        }

        /** 清空文件读取记录（/clear 开新会话时调用）。 */
        public void reset() {
            lock.lock();
            try {
                files.clear();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 构造恢复三段内容：最近文件快照（前 RECOVERY_FILE_LIMIT 个）+ 当前工具列表 + 边界提示。
     * 返回 String（不返回 Message）：runSummary 会把摘要文本与本方法输出拼到同一条 user 消息里。
     */
    public static String buildRecoveryAttachment(List<FileReadRecord> snapshot, List<ToolDef> toolDefs) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 最近读过的文件\n");
        List<FileReadRecord> top = snapshot.size() > CompactConstants.RECOVERY_FILE_LIMIT
                ? snapshot.subList(0, CompactConstants.RECOVERY_FILE_LIMIT)
                : snapshot;
        if (top.isEmpty()) {
            sb.append("(无)\n");
        } else {
            for (FileReadRecord rec : top) {
                sb.append(renderFileBlock(rec));
            }
        }
        sb.append("## 当前可用工具\n");
        sb.append(renderToolsBlock(toolDefs));
        sb.append("## 边界提示\n");
        sb.append(BOUNDARY_NOTICE).append('\n');
        return sb.toString();
    }

    /** 渲染单个文件快照：路径 / 读取时间 / 内容片段（必要时截断并加 (content truncated) 标注）。 */
    static String renderFileBlock(FileReadRecord rec) {
        int charLimit = (int) (CompactConstants.RECOVERY_TOKENS_PER_FILE
                * CompactConstants.ESTIMATE_CHARS_PER_TOKEN);
        String content = rec.content();
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(rec.path()).append('\n');
        sb.append("[read at] ").append(rec.timestamp()).append('\n');
        if (content != null && content.length() > charLimit) {
            sb.append(content, 0, charLimit).append("\n(content truncated)\n");
        } else {
            sb.append(content == null ? "" : content).append('\n');
        }
        return sb.toString();
    }

    /** 渲染工具列表：每行一个工具名 + 用途 + 参数 schema 摘要。 */
    static String renderToolsBlock(List<ToolDef> defs) {
        StringBuilder sb = new StringBuilder();
        for (ToolDef d : defs) {
            sb.append("- ").append(d.name()).append(": ").append(d.description()).append('\n');
            sb.append("  schema: ").append(d.inputSchema()).append('\n');
        }
        return sb.toString();
    }
}
