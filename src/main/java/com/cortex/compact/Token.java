package com.cortex.compact;

import com.cortex.conversation.Message;
import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolResult;
import com.cortex.llm.Usage;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Token 估算（纯函数，不调用精确 tokenizer）。
 * 锚定上一次 provider 返回的真实 usage（{@link #usageAnchor}），
 * 对其后新增消息按「字符数 / ESTIMATE_CHARS_PER_TOKEN」做增量估算（F13）。
 */
public final class Token {

    private Token() {}

    /** 把一轮请求的 usage 合并成单一锚点值：input + output + cacheRead + cacheWrite。 */
    public static long usageAnchor(Usage u) {
        return u.inputTokens() + u.outputTokens() + u.cacheRead() + u.cacheWrite();
    }

    /** 计算单段消息列表的字符总量（UTF-8 字节，含 toolCalls/toolResults）。 */
    static int messageChars(List<Message> msgs) {
        int total = 0;
        for (Message m : msgs) {
            if (m.getContent() != null) {
                total += m.getContent().getBytes(StandardCharsets.UTF_8).length;
            }
            for (ToolCall c : m.getToolCalls()) {
                if (c.args() != null) {
                    total += c.args().getBytes(StandardCharsets.UTF_8).length;
                }
            }
            for (ToolResult r : m.getToolResults()) {
                if (r.content() != null) {
                    total += r.content().getBytes(StandardCharsets.UTF_8).length;
                }
            }
        }
        return total;
    }

    /**
     * 估算当前消息列表消耗的 token。
     * <p>
     * 入参语义：anchor 是上一次主对话 Stream 真实 usage 之和，anchorMsgLen 是当时
     * conversation.size()；本函数只对 anchorMsgLen 之后追加的消息做字符增量估算，
     * 避免把已含在 anchor 里的历史重复计算。锚点为 0、anchorMsgLen 为 0（首轮 / 摘要后）
     * 时退化为纯字符估算。
     * <p>
     * 入参 allMsgs 必须是已经经过第 1 层 offloadAndSnip 处理（layer1 之后）的消息列表；
     * 否则估算偏高，会过早触发第 2 层。
     */
    public static long estimateTokens(long anchor, List<Message> allMsgs, int anchorMsgLen) {
        int safeStart = Math.min(Math.max(anchorMsgLen, 0), allMsgs.size());
        List<Message> tail = allMsgs.subList(safeStart, allMsgs.size());
        return anchor + (long) Math.ceil(messageChars(tail) / CompactConstants.ESTIMATE_CHARS_PER_TOKEN);
    }
}
