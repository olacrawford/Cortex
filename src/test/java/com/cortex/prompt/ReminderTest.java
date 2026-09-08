package com.cortex.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReminderTest {

    @Test
    void systemReminder用标签包裹() {
        String s = Reminder.systemReminder("正文内容");
        assertEquals("<system-reminder>\n正文内容\n</system-reminder>", s);
    }

    @Test
    void 完整版含规划模式全文() {
        String s = Reminder.plan(true);
        assertTrue(s.contains("<system-reminder>"));
        assertTrue(s.contains("PLAN MODE"));
        assertTrue(s.contains("read_file, glob, grep"));
        assertTrue(s.contains("/do"));
    }

    @Test
    void 精简版不含完整版文案() {
        String concise = Reminder.plan(false);
        assertTrue(concise.contains("<system-reminder>"));
        assertTrue(concise.contains("Plan mode is still active"));
        assertFalse(concise.contains("You are currently in PLAN MODE")); // 与完整版可区分
    }
}
