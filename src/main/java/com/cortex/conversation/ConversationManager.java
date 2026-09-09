package com.cortex.conversation;

import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 对话历史管理：维护消息序列，供 LLM 请求组装上下文。
 * 线程约定：一轮对话内由提交线程与 Agent 虚拟线程先后独占，不做并发保护；
 * ch08 起整体加 {@link ReentrantLock}，并在压缩摘要后支持整体替换（replaceMessages）。
 * <p>
 * ch09：支持 {@code onAppend} / {@code onReplace} 回调，用于把每次消息追加 / 整体替换
 * 实时同步到 session JSONL 存档。未设置回调时行为与 ch08 完全一致。
 */
public class ConversationManager {

    private final ReentrantLock lock = new ReentrantLock();
    private final List<Message> messages = new ArrayList<>();
    private final Consumer<Message> onAppend;
    private final Consumer<List<Message>> onReplace;

    public ConversationManager() {
        this(null, null);
    }

    public ConversationManager(Consumer<Message> onAppend, Consumer<List<Message>> onReplace) {
        this.onAppend = onAppend;
        this.onReplace = onReplace;
    }

    /** 用已有消息初始化（深拷贝），并挂接回调（用于 /resume 恢复会话）。 */
    public static ConversationManager fromMessages(List<Message> msgs, Consumer<Message> onAppend,
                                                   Consumer<List<Message>> onReplace) {
        ConversationManager cm = new ConversationManager(onAppend, onReplace);
        if (msgs != null) {
            for (Message m : msgs) {
                cm.messages.add(copyMessage(m));
            }
        }
        return cm;
    }

    public void addUserMessage(String text) {
        Message msg;
        lock.lock();
        try {
            msg = new Message(Message.Role.USER, text);
            messages.add(msg);
        } finally {
            lock.unlock();
        }
        if (onAppend != null) {
            onAppend.accept(msg);
        }
    }

    public void addAssistantMessage(String text) {
        Message msg;
        lock.lock();
        try {
            msg = new Message(Message.Role.ASSISTANT, text);
            messages.add(msg);
        } finally {
            lock.unlock();
        }
        if (onAppend != null) {
            onAppend.accept(msg);
        }
    }

    /** 追加一个 assistant 工具调用回合（可同时携带 preamble 文本）。 */
    public void addAssistantWithToolCalls(String text, List<ToolCall> calls) {
        Message msg;
        lock.lock();
        try {
            msg = new Message(Message.Role.ASSISTANT, text, calls, List.of());
            messages.add(msg);
        } finally {
            lock.unlock();
        }
        if (onAppend != null) {
            onAppend.accept(msg);
        }
    }

    /** 追加一个工具结果回合（一条消息可携带多个结果）。 */
    public void addToolResults(List<ToolResult> results) {
        Message msg;
        lock.lock();
        try {
            msg = new Message(Message.Role.TOOL, "", List.of(), results);
            messages.add(msg);
        } finally {
            lock.unlock();
        }
        if (onAppend != null) {
            onAppend.accept(msg);
        }
    }

    /**
     * 把内存数组整体替换为传入的 msgs（做深拷贝，含 toolCalls / toolResults 列表）。
     * compact 摘要后用这个方法一次性丢弃旧历史并装入「摘要 + 恢复 + 近期原文」。
     */
    public void replaceMessages(List<Message> msgs) {
        lock.lock();
        try {
            messages.clear();
            if (msgs != null) {
                for (Message m : msgs) {
                    messages.add(copyMessage(m));
                }
            }
        } finally {
            lock.unlock();
        }
        if (onReplace != null) {
            onReplace.accept(msgs == null ? List.of() : List.copyOf(msgs));
        }
    }

    private static Message copyMessage(Message m) {
        return new Message(m.getRole(), m.getContent(),
                new ArrayList<>(m.getToolCalls()), new ArrayList<>(m.getToolResults()));
    }

    public List<Message> getMessages() {
        lock.lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(messages));
        } finally {
            lock.unlock();
        }
    }

    /** 最后一条消息的角色；空历史返回 empty。用于终止收尾时判断历史尾巴。 */
    public Optional<Message.Role> lastRole() {
        lock.lock();
        try {
            if (messages.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(messages.get(messages.size() - 1).getRole());
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            messages.clear();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return messages.size();
        } finally {
            lock.unlock();
        }
    }
}
