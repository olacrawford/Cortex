package com.cortex.agent;

import java.util.Optional;

/**
 * Agent 工具对子 Agent 角色编目中心的窄接口（T17）：agent 包不直接依赖 subagent 包的
 * Catalog 类型，由 {@code subagent.Catalog} 实现本接口（打破 agent ↔ subagent 循环依赖）。
 */
public interface AgentCatalogPort {

    /** 按名解析角色定义；无命中返回 empty。 */
    Optional<com.cortex.subagent.Definition> resolve(String name);

    /** Fork 路径的临时定义（name=__fork__）。 */
    com.cortex.subagent.Definition forkDefinition();

    /** 全部定义（按 name 升序），Agent 工具的 subagent_type 文档用。 */
    java.util.List<com.cortex.subagent.Definition> list();
}
