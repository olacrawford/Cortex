package com.cortex.llm;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicSystemTest {

    @Test
    void 稳定块带缓存断点_环境块不带() {
        MessageCreateParams.System system = AnthropicClient.toAnthropicSystem(
                new SystemPrompt("稳定系统提示", "环境信息"));

        List<TextBlockParam> blocks = system.asTextBlockParams();
        assertEquals(2, blocks.size());
        assertEquals("稳定系统提示", blocks.get(0).text());
        assertTrue(blocks.get(0).cacheControl().isPresent(), "稳定块必须打缓存断点");
        assertEquals("环境信息", blocks.get(1).text());
        assertTrue(blocks.get(1).cacheControl().isEmpty(), "环境块不得打缓存断点");
    }

    @Test
    void 空段省略() {
        MessageCreateParams.System onlyStable = AnthropicClient.toAnthropicSystem(
                new SystemPrompt("只有稳定块", ""));
        assertEquals(1, onlyStable.asTextBlockParams().size());
        assertTrue(onlyStable.asTextBlockParams().get(0).cacheControl().isPresent());

        MessageCreateParams.System onlyEnv = AnthropicClient.toAnthropicSystem(
                new SystemPrompt("", "只有环境块"));
        assertEquals(1, onlyEnv.asTextBlockParams().size());
        assertTrue(onlyEnv.asTextBlockParams().get(0).cacheControl().isEmpty());
    }
}
