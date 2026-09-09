package com.cortex.agent;

import com.cortex.conversation.ConversationManager;
import com.cortex.subagent.Definition;
import com.cortex.tool.Filter;
import com.cortex.tool.Result;
import com.cortex.tool.Tool;
import com.cortex.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 统一 Agent 工具（F1-F3/G1）：主 Agent 通过 subagent_type 分流「定义式」与「Fork 式」两条路径。
 * 前台同步跑至多 AUTO_BACKGROUND_MS（超时自动转后台，F17-②）；显式 run_in_background / Fork
 * 强制走后台（F17-①/F18）。工具列表对模型始终稳定——角色定义增减只影响 description 文案（N1）。
 * <p>
 * 嵌套阻断三道闸（F24/F26/F3）：定义式子 Agent 工具列表剔除 Agent 工具（Filter）；
 * QuerySource（ThreadLocal 调用方）识别 Fork 子 Agent；boilerplate 标记扫描兜底。
 */
public final class AgentTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 前台子 Agent 自动切后台阈值（F17-②）；tmux 测试可用
     * {@code -Dcortex.subagent.autoBackgroundMs=5000} 调小。
     */
    public static final long AUTO_BACKGROUND_MS =
            Long.getLong("cortex.subagent.autoBackgroundMs", 120_000L);

    private final AgentCatalogPort catalog;
    private final TaskManagerPort taskMgr;
    private final boolean bgEnabled;      // N6 配置开关 enableSubAgentBackground
    private volatile Agent parent;        // 主 Agent（provider/registry/engine/runtime 来源）

    public AgentTool(AgentCatalogPort catalog, TaskManagerPort taskMgr, boolean bgEnabled) {
        this.catalog = catalog;
        this.taskMgr = taskMgr;
        this.bgEnabled = bgEnabled;
    }

    /** 主 Agent 就绪后回填（多 provider 选择 / activate 之后，T29）。 */
    public void setParent(Agent parent) {
        this.parent = parent;
    }

    @Override
    public String name() {
        return "Agent";
    }

    @Override
    public boolean readOnly() {
        return false; // 子 Agent 可能做任何事（EXEC 类，走 Ask）
    }

    /** per-tool 超时：必须大于前台自动切后台阈值，否则外层 await 会先杀死前台等待。 */
    @Override
    public Duration timeout() {
        return bgEnabled
                ? Duration.ofMillis(AUTO_BACKGROUND_MS + 30_000L)
                : Duration.ofMinutes(10);
    }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder(
                "把独立子任务委派给一个拥有干净上下文的子 Agent 执行，返回其最终报告。"
                        + "不传 subagent_type 时走 Fork 路径（继承当前对话历史并行执行）。可用 subagent_type：");
        List<Definition> defs = catalog.list();
        for (int i = 0; i < defs.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(defs.get(i).name()).append("（").append(defs.get(i).description()).append("）");
        }
        return sb.toString();
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "prompt", Map.of("type", "string", "description", "交给子 Agent 的任务指令"),
                        "description", Map.of("type", "string", "description", "一句话描述任务（供 UI 展示）"),
                        "subagent_type", Map.of("type", "string",
                                "description", "预定义角色名；留空走 Fork 路径（继承父对话历史，后台执行）"),
                        "model", Map.of("type", "string",
                                "enum", List.of("haiku", "sonnet", "opus", "inherit"),
                                "description", "模型覆盖；本期记录不切换 provider"),
                        "run_in_background", Map.of("type", "boolean",
                                "description", "true 时后台启动，立即返回 task_id"),
                        "name", Map.of("type", "string",
                                "description", "给本次子 Agent 命名，供 SendMessage 续派；同名后启动覆盖前者")),
                "required", List.of("prompt", "description"));
    }

    @Override
    public Result execute(String argsJson) {
        JsonNode args;
        try {
            args = MAPPER.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
        } catch (Exception e) {
            return Result.error("参数不是合法 JSON: " + e.getMessage());
        }
        String prompt = text(args, "prompt");
        String description = text(args, "description");
        String subagentType = text(args, "subagent_type");
        String name = text(args, "name");
        boolean runInBackground = args.has("run_in_background") && args.get("run_in_background").isBoolean()
                && args.get("run_in_background").asBoolean();

        if (prompt == null || prompt.isBlank()) {
            return Result.error("缺少必填参数 prompt");
        }
        if (description == null || description.isBlank()) {
            return Result.error("缺少必填参数 description");
        }
        Agent parentAgent = parent;
        if (parentAgent == null) {
            return Result.error("Agent 工具未就绪（主 Agent 尚未初始化）");
        }

        // ── 嵌套阻断（F24 闸②③）：调用方是 Fork 子 Agent，或其对话含 boilerplate 标记 ──
        Agent caller = Agent.currentCaller();
        if (caller != null) {
            boolean forkCaller = caller.isForkContext()
                    || (caller.currentConversation() != null
                        && Fork.isForkContext(caller.currentConversation().getMessages()));
            if (forkCaller) {
                return Result.error("Fork 子 Agent 不能再启动 Agent");
            }
        }

        // ── 解析定义（F2）：subagent_type 非空走 catalog，空走 Fork ──
        boolean fork = subagentType == null || subagentType.isBlank();
        Definition def;
        if (fork) {
            def = catalog.forkDefinition();
        } else {
            def = catalog.resolve(subagentType.strip()).orElse(null);
            if (def == null) {
                return Result.error("未知 subagent_type: " + subagentType
                        + "（可用: " + catalog.list().stream().map(Definition::name)
                        .reduce((a, b) -> a + ", " + b).orElse("（无）") + "）");
            }
        }

        // ── 后台决策（F17/F18/N6）──
        boolean explicitBackground = runInBackground || def.background();
        boolean forceBackground = fork || explicitBackground;
        if (fork && !bgEnabled) {
            return Result.error("后台禁用（enableSubAgentBackground=false），无法 Fork");
        }

        // ── 工具过滤多层防线（F30）──
        ToolRegistry registry = parentAgent.registry();
        List<String> allNames = registry.definitions().stream().map(com.cortex.llm.ToolDef::name).toList();
        List<String> allowed = Filter.applyAgentToolFilter(new Filter.FilterParams(
                allNames,
                def.source().ordinal() + 1,
                explicitBackground, // Fork 强制后台不计入白名单（保留完整父工具集，N2/AC5）
                fork,
                def.tools(),
                def.disallowedTools()));

        // ── 构造子 Agent（G2：状态隔离、基础设施共享）──
        SessionRuntime subRuntime = SessionRuntime.empty(parentAgent.runtime().contextWindow);
        subRuntime.hookEngine = parentAgent.runtime().hookEngine; // 共享 Hook 引擎（F11）
        Agent.Builder builder = Agent.builder(parentAgent.client(), registry, parentAgent.version(),
                        parentAgent.engine(), subRuntime)
                .allowedTools(Set.copyOf(allowed))
                .maxTurns(def.maxTurns())
                .permissionMode(def.permissionMode())
                .dontAsk(def.dontAsk())
                .approvalUpgrader(taskMgr::upgradeApproval);
        if (fork) {
            builder.forkContext(true); // QuerySource 闸：Fork 子 Agent 调 Agent 工具直接拦截
        } else {
            builder.systemPrompt(def.systemPrompt()); // 定义式：角色 prompt 覆盖（F10）
        }
        Agent subAgent = builder.build();

        // ── 子对话（G3）：Fork 克隆父历史 + boilerplate；定义式从空白开始 ──
        ConversationManager subConv;
        String taskText;
        if (fork) {
            ConversationManager parentConv = parentAgent.currentConversation();
            List<com.cortex.conversation.Message> parentMsgs =
                    parentConv == null ? List.of() : parentConv.getMessages();
            subConv = ConversationManager.fromMessages(
                    Fork.buildForkedMessages(parentMsgs, prompt), null, null);
            taskText = ""; // 消息已装填，runToCompletion 空串不追加
        } else {
            subConv = new ConversationManager();
            taskText = prompt;
        }

        // ── 启动 ──
        if (forceBackground && bgEnabled) {
            String id = taskMgr.launch(subAgent, subConv, name, taskText);
            return Result.ok("{\"task_id\":\"" + id + "\",\"status\":\"async_launched\"}");
        }
        if (forceBackground) { // bgEnabled=false 且非 Fork（run_in_background/定义 background）：强制前台（N6）
            return runForegroundSync(subAgent, subConv, taskText);
        }
        if (!bgEnabled) {
            return runForegroundSync(subAgent, subConv, taskText);
        }

        // 前台：等至多 AUTO_BACKGROUND_MS，超时自动转后台（F17-②）
        SubAgentRun run = taskMgr.startForeground(subAgent, subConv, name, taskText);
        try {
            if (run.awaitCompletion(AUTO_BACKGROUND_MS)) {
                if (run.failed()) {
                    return Result.error("子 Agent 执行失败: " + run.errorMessage());
                }
                return Result.ok(run.resultText() == null ? "（子 Agent 无文本输出。）" : run.resultText());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error(Agent.NOTICE_CANCELLED);
        }
        return Result.ok("{\"task_id\":\"" + run.id() + "\",\"status\":\"timed_out_to_background\"}");
    }

    /** 完全前台同步（N6 后台禁用路径）：不留任务记录，直接跑完返回。 */
    private Result runForegroundSync(Agent subAgent, ConversationManager subConv, String taskText) {
        try {
            String text = subAgent.runToCompletion(new CancelToken(), subConv, taskText,
                    new LinkedBlockingQueue<>());
            return Result.ok(text == null || text.isBlank() ? "（子 Agent 无文本输出。）" : text);
        } catch (java.util.concurrent.CancellationException ce) {
            return Result.error(Agent.NOTICE_CANCELLED);
        } catch (Agent.MaxTurnsReachedException mt) {
            return Result.ok((mt.lastAssistantText() == null || mt.lastAssistantText().isBlank()
                    ? "（子 Agent 达到最大轮数。" : mt.lastAssistantText()) + "）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error(Agent.NOTICE_CANCELLED);
        } catch (Exception e) {
            return Result.error("子 Agent 执行失败: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    private static String text(JsonNode args, String field) {
        return args.has(field) && args.get(field).isTextual() ? args.get(field).asText() : null;
    }
}
