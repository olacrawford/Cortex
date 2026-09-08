package com.cortex.smoke;

import com.cortex.agent.Agent;
import com.cortex.agent.AgentEvent;
import com.cortex.agent.CancelToken;
import com.cortex.config.AppConfig;
import com.cortex.config.ConfigLoader;
import com.cortex.conversation.ConversationManager;
import com.cortex.llm.LlmClient;
import com.cortex.permission.Mode;
import com.cortex.permission.PermissionEngine;
import com.cortex.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;

/**
 * 缓存策略验证烟囱（F4/场景1）：非交互跑两轮消息，逐轮打印
 * input/output/cache_write/cache_read——次轮 cache_read > 0 即稳定前缀被缓存复用。
 * 用法：java -cp cortex.jar com.cortex.smoke.SmokeMain [provider序号] [消息1] [消息2] …
 */
public final class SmokeMain {

    private SmokeMain() {}

    public static void main(String[] args) throws Exception {
        AppConfig config = ConfigLoader.load(".cortex/config.yaml");
        int providerIndex = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        List<String> messages = args.length > 1
                ? List.of(args).subList(1, args.length)
                : List.of("用一句话介绍你自己", "你的工作目录里有什么顶层文件？只列名字");
        var providerCfg = config.getProviders().get(providerIndex);

        LlmClient client = LlmClient.create(providerCfg);
        ConversationManager conv = new ConversationManager();
        // 非交互无法人在回路：BYPASS 跳过 Ask（黑名单/沙箱仍拦）；用例文件操作须落 cwd 内
        PermissionEngine engine = PermissionEngine.create(Path.of("").toAbsolutePath());
        Agent agent = new Agent(client, ToolRegistry.createDefault(), "smoke", engine);
        CancelToken cancel = new CancelToken();

        System.out.println("provider=" + providerCfg.getName() + " protocol=" + providerCfg.getProtocol());
        for (String msg : messages) {
            conv.addUserMessage(msg);
            System.out.println("\n>>> " + msg);
            BlockingQueue<AgentEvent> queue = agent.run(conv, Mode.DEFAULT, cancel);
            while (true) {
                AgentEvent e = queue.take();
                if (e instanceof AgentEvent.Text t) {
                    System.out.print(t.delta());
                } else if (e instanceof AgentEvent.Tool te) {
                    System.out.println("\n[tool " + te.event().phase() + " " + te.event().name() + "]");
                } else if (e instanceof AgentEvent.UsageReport u) {
                    System.out.printf("%n[usage] input=%d output=%d cache_write=%d cache_read=%d%n",
                            u.usage().inputTokens(), u.usage().outputTokens(),
                            u.usage().cacheWrite(), u.usage().cacheRead());
                } else if (e instanceof AgentEvent.Notice n) {
                    System.out.println("\n[notice] " + n.message());
                } else if (e instanceof AgentEvent.Failed f) {
                    System.out.println("\n[failed] " + f.message());
                    break;
                } else if (e instanceof AgentEvent.Done) {
                    System.out.println("\n--- turn done ---");
                    break;
                }
            }
        }
        System.exit(0);
    }
}
