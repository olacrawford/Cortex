package com.cortex.conversation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationTest {

    @Test
    void 添加消息并读取() {
        ConversationManager cm = new ConversationManager();
        cm.addUserMessage("你好");
        cm.addAssistantMessage("你好！有什么可以帮你的？");

        List<Message> msgs = cm.getMessages();
        assertEquals(2, msgs.size());
        assertEquals(Message.Role.USER, msgs.get(0).getRole());
        assertEquals("你好", msgs.get(0).getContent());
        assertEquals(Message.Role.ASSISTANT, msgs.get(1).getRole());
        assertEquals("你好！有什么可以帮你的？", msgs.get(1).getContent());
    }

    @Test
    void 多轮对话() {
        ConversationManager cm = new ConversationManager();
        cm.addUserMessage("第一轮");
        cm.addAssistantMessage("回复1");
        cm.addUserMessage("第二轮");
        cm.addAssistantMessage("回复2");

        assertEquals(4, cm.size());
    }

    @Test
    void clear清空历史() {
        ConversationManager cm = new ConversationManager();
        cm.addUserMessage("测试");
        cm.clear();
        assertEquals(0, cm.size());
    }

    @Test
    void getMessages返回不可修改列表() {
        ConversationManager cm = new ConversationManager();
        cm.addUserMessage("测试");
        assertThrows(UnsupportedOperationException.class,
                () -> cm.getMessages().add(new Message(Message.Role.USER, "x")));
    }

    @Test
    void replaceMessages深拷贝() {
        ConversationManager cm = new ConversationManager();
        cm.addUserMessage("旧");
        List<Message> newMsgs = new java.util.ArrayList<>();
        newMsgs.add(new Message(Message.Role.USER, "新1"));
        newMsgs.add(new Message(Message.Role.ASSISTANT, "新2"));
        cm.replaceMessages(newMsgs);
        // 修改入参列表不影响 conversation
        newMsgs.add(new Message(Message.Role.USER, "污染"));
        assertEquals(2, cm.size());
        assertEquals("新1", cm.getMessages().get(0).getContent());
        assertEquals("新2", cm.getMessages().get(1).getContent());
    }

    @Test
    void replaceMessages空列表() {
        ConversationManager cm = new ConversationManager();
        cm.addUserMessage("旧");
        cm.replaceMessages(null);
        assertEquals(0, cm.size());
        cm.replaceMessages(List.of());
        assertEquals(0, cm.size());
    }
}