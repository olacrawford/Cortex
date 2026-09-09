package com.cortex.agent;

import com.cortex.conversation.Message;
import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fork 路径辅助（F22-F24）：克隆父对话消息、修复悬空 tool_use、注入 Fork Boilerplate，
 * 以及嵌套兜底检测（boilerplate 标记扫描）。
 */
public final class Fork {

    /** Fork Boilerplate 标签（消息扫描的判定标记，F24 闸③）。 */
    public static final String FORK_BOILERPLATE_TAG = "<fork_boilerplate>";

    /** Fork 子 Agent 首条 user 消息的前缀约束（F23）。 */
    public static final String FORK_BOILERPLATE = """
            <fork_boilerplate>
            你是一个 Fork 出来的工作进程。你不是主 Agent。
            规则（不可协商）：
            1. 不能再 Fork（调用 Agent 工具会被拦截）。
            2. 不要对话、不要提问、不要请求确认。
            3. 直接使用工具：读文件、搜索代码、做修改。
            4. 严格限制在你被分配的任务范围内。
            5. 最终报告以 "Scope:" 开头，500 字以内。
            </fork_boilerplate>

            """;

    private Fork() {}

    /**
     * 把父对话克隆为 Fork 子对话的消息列表（F22）：
     * ① 深拷贝全部消息；② 末尾未配对的 tool_use 补 placeholder ToolResult（消息格式合法化）；
     * ③ 追加 user 消息 = FORK_BOILERPLATE + task。
     */
    public static List<Message> buildForkedMessages(List<Message> parentMsgs, String task) {
        List<Message> out = new ArrayList<>();
        Set<String> pendingCallIds = new HashSet<>();
        for (Message m : parentMsgs) {
            out.add(copy(m));
            for (ToolCall c : m.getToolCalls()) {
                pendingCallIds.add(c.id());
            }
            for (ToolResult r : m.getToolResults()) {
                pendingCallIds.remove(r.toolCallId());
            }
        }
        if (!pendingCallIds.isEmpty()) {
            List<ToolResult> placeholders = new ArrayList<>();
            for (String id : pendingCallIds) {
                placeholders.add(new ToolResult(id, "[forked, skipped]", true));
            }
            out.add(new Message(Message.Role.TOOL, "", List.of(), placeholders));
        }
        out.add(new Message(Message.Role.USER, FORK_BOILERPLATE + (task == null ? "" : task)));
        return out;
    }

    /** 嵌套兜底检测（F24 闸③）：任一消息内容含 boilerplate 标记即认定为 Fork 上下文。 */
    public static boolean isForkContext(List<Message> msgs) {
        if (msgs == null) {
            return false;
        }
        for (Message m : msgs) {
            String c = m.getContent();
            if (c != null && c.contains(FORK_BOILERPLATE_TAG)) {
                return true;
            }
        }
        return false;
    }

    private static Message copy(Message m) {
        return new Message(m.getRole(), m.getContent(),
                new ArrayList<>(m.getToolCalls()), new ArrayList<>(m.getToolResults()));
    }
}
