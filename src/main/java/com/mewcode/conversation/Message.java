package com.mewcode.conversation;

public class Message {
    public enum Role {
        USER,
        ASSISTANT
    }

    private final Role role;
    private final String content;

    public Message(Role role, String content) {
        this.role = role;
        this.content = content;
    }

    public Role getRole() { return role; }
    public String getContent() { return content; }
}