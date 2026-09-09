package com.cortex.agent;

import com.cortex.conversation.ConversationManager;
import com.cortex.conversation.Message;
import com.cortex.tool.Filter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 公共 Fork 启动函数（T28/T31/F33）：Skill fork 与 Agent 工具的 Fork 路径共用同一段
 * 子 Agent 构造、工具过滤与消息装填逻辑。放在 agent 包因为它要构造 {@link Agent}
 * （subagent / skill 包单向依赖 agent，避免循环）。
 */
public final class LaunchFork {

    private LaunchFork() {}

    /**
     * 起一个隔离子 Agent 跑完任务并返回最终文本（前台同步，G5）。
     * 子 Agent 继承父系统提示（N2 缓存一致）与基础设施（client/registry/engine/hookEngine，F11），
     * 工具集 = 父全量 − 全局禁止列表（再按 allowedTools 收窄）。
     *
     * @param parent       主 Agent（装配来源）
     * @param seed         种子消息（fork_context 生成的父对话快照，可空）
     * @param body         渲染后的任务正文（作为末尾 user 消息）
     * @param allowedTools 工具白名单（空 = 只应用全局禁止列表）
     * @param cancel       取消句柄
     */
    public static String launch(Agent parent, List<Message> seed, String body,
                                List<String> allowedTools, CancelToken cancel) throws InterruptedException {
        List<String> allNames = parent.registry().definitions().stream()
                .map(com.cortex.llm.ToolDef::name).toList();
        List<String> allowed = Filter.applyAgentToolFilter(new Filter.FilterParams(
                allNames, 1, false, false,
                allowedTools == null ? List.of() : allowedTools, List.of()));

        SessionRuntime subRuntime = SessionRuntime.empty(parent.runtime().contextWindow);
        subRuntime.hookEngine = parent.runtime().hookEngine;
        Agent sub = Agent.builder(parent.client(), parent.registry(), parent.version(),
                        parent.engine(), subRuntime)
                .allowedTools(Set.copyOf(allowed))
                .forkContext(true) // skill-fork 同样受嵌套阻断（F24 QuerySource）
                .build();

        List<Message> msgs = seed == null ? new ArrayList<>() : seed;
        ConversationManager conv = ConversationManager.fromMessages(msgs, null, null);
        conv.addUserMessage(body);
        return sub.runToCompletion(cancel, conv, "", null);
    }
}
