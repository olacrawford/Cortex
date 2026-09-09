package com.cortex.conversation;

import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 对话历史管理：维护消息序列，供 LLM 请求组装上下文。
 * 线程约定：一轮对话内由提交线程与 Agent 虚拟线程先后独占，不做并发保护；
 * ch08 起整体加 {@link ReentrantLock}，并在压缩摘要后支持整体替换（replaceMessages）。
 */
public class ConversationManager {

    private final ReentrantLock lock = new ReentrantLock();
    private final List<Message> messages = new ArrayList<>();

    public void addUserMessage(String text) {
        lock.lock();
        try {
            messages.add(new Message(Message.Role.USER, text));
        } finally {
            lock.unlock();
        }
    }

    public void addAssistantMessage(String text) {
        lock.lock();
        try {
            messages.add(new Message(Message.Role.ASSISTANT, text));
        } finally {
            lock.unlock();
        }
    }

    /** 追加一个 assistant 工具调用回合（可同时携带 preamble 文本）。 */
    public void addAssistantWithToolCalls(String text, List<ToolCall> calls) {
        lock.lock();
        try {
            messages.add(new Message(Message.Role.ASSISTANT, text, calls, List.of()));
        } finally {
            lock.unlock();
        }
    }

    /** 追加一个工具结果回合（一条消息可携带多个结果）。 */
    public void addToolResults(List<ToolResult> results) {
        lock.lock();
        try {
            messages.add(new Message(Message.Role.TOOL, "", List.of(), results));
        } finally {
            lock.unlock();
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
