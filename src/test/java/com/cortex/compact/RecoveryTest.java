package com.cortex.compact;

import com.cortex.compact.Recovery.FileReadRecord;
import com.cortex.llm.ToolDef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {

    private static FileReadRecord rec(String path, int chars, Instant ts) {
        return new FileReadRecord(path, "x".repeat(chars), ts);
    }

    @Test
    void 文件快照超长保留头部截尾部() {
        int charLimit = (int) (CompactConstants.RECOVERY_TOKENS_PER_FILE
                * CompactConstants.ESTIMATE_CHARS_PER_TOKEN);
        FileReadRecord rec = new FileReadRecord("/a", "y".repeat(charLimit + 100), Instant.now());
        String block = Recovery.renderFileBlock(rec);
        assertTrue(block.contains("(content truncated)"));
        assertTrue(block.contains("### /a"));
        // 头部保留前 charLimit 字符，尾部被截掉
        assertTrue(block.contains("y".repeat(charLimit)));
    }

    @Test
    void 恢复段只展示最近5个() {
        List<FileReadRecord> snapshot = new ArrayList<>();
        // 按时间戳倒序排列（同 RecoveryState.snapshot() 的输出）
        for (int i = 6; i >= 0; i--) {
            snapshot.add(rec("/f" + i, 10, Instant.ofEpochSecond(1000 + i)));
        }
        String text = Recovery.buildRecoveryAttachment(snapshot, List.of());
        // 最近 5 个：/f6 到 /f2
        assertTrue(text.contains("/f6"));
        assertTrue(text.contains("/f2"));
        assertFalse(text.contains("/f1"), "第 6 个不应出现: " + text);
        assertFalse(text.contains("/f0"), "第 7 个不应出现: " + text);
        // 倒序：/f6 在 /f5 之前
        assertTrue(text.indexOf("/f6") < text.indexOf("/f5"));
    }

    @Test
    void 恢复段工具集合一致() {
        List<ToolDef> defs = List.of(
                new ToolDef("read_file", "读文件", Map.of("type", "object")),
                new ToolDef("grep", "搜索", Map.of("type", "object")));
        String text = Recovery.buildRecoveryAttachment(List.of(), defs);
        assertTrue(text.contains("- read_file:"));
        assertTrue(text.contains("- grep:"));
        assertTrue(text.contains("## 当前可用工具"));
    }

    @Test
    void 边界提示固定文案稳定() {
        List<ToolDef> defs = List.of(new ToolDef("read_file", "读文件", Map.of("type", "object")));
        String t1 = Recovery.buildRecoveryAttachment(List.of(), defs);
        String t2 = Recovery.buildRecoveryAttachment(List.of(), defs);
        assertEquals(t1, t2);
        assertTrue(t1.contains(Recovery.BOUNDARY_NOTICE));
        assertTrue(t1.contains("## 边界提示"));
    }

    @Test
    void 空文件快照显示无() {
        String text = Recovery.buildRecoveryAttachment(List.of(), List.of());
        assertTrue(text.contains("(无)"));
    }
}
