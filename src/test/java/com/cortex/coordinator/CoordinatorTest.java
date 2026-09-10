package com.cortex.coordinator;

import com.cortex.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Coordinator Mode 双锁（T24/F52-F55/AC21/AC22）。 */
class CoordinatorTest {

    @Test
    void 配置锁关闭时恒不生效() {
        AppConfig off = new AppConfig();
        off.setFeatures(new AppConfig.Features(false, false));
        assertFalse(Coordinator.isEnabled(off));
        AppConfig nullFeatures = new AppConfig();
        assertFalse(Coordinator.isEnabled(nullFeatures));
        assertFalse(Coordinator.isEnabled(null));
    }

    @Test
    void envTruthy语义() {
        assertTrue(Coordinator.envTruthy("1"));
        assertTrue(Coordinator.envTruthy("TRUE"));
        assertTrue(Coordinator.envTruthy("Yes"));
        assertFalse(Coordinator.envTruthy("0"));
        assertFalse(Coordinator.envTruthy("no"));
        assertFalse(Coordinator.envTruthy(null));
    }

    @Test
    void 白名单剥夺写工具() {
        List<String> allowed = Coordinator.ALLOWED_TOOLS;
        assertTrue(allowed.contains("Agent"));
        assertTrue(allowed.contains("TaskCreate"));
        assertTrue(allowed.contains("SendMessage"));
        assertTrue(allowed.contains("bash"));
        assertFalse(allowed.contains("write_file"), "AC21：剥夺 write_file");
        assertFalse(allowed.contains("edit_file"), "AC21：剥夺 edit_file");
    }

    @Test
    void 提示词含纪律段() {
        String prompt = Coordinator.systemPromptSuffix();
        assertTrue(prompt.contains("Coordinator Mode"));
        assertTrue(prompt.contains("禁止"));
        assertTrue(prompt.contains("git merge"));
    }
}
