package com.mewcode.conversation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ConversationManager {

    private final List<Message> messages = new ArrayList<>();

    public void addUserMessage(String text) {
        messages.add(new Message(Message.Role.USER, text));
    }

    public void addAssistantMessage(String text) {
        messages.add(new Message(Message.Role.ASSISTANT, text));
    }

    public List<Message> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public void clear() {
        messages.clear();
    }

    public int size() {
        return messages.size();
    }
}