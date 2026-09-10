package com.cortex.team;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** AgentNameRegistry（T7/F35-F38）。 */
class AgentNameRegistryTest {

    @Test
    void register_resolve_nameOf() {
        AgentNameRegistry reg = new AgentNameRegistry();
        reg.register("alice", "agent-a");
        assertEquals(Optional.of("agent-a"), reg.resolve("alice"));
        assertEquals(Optional.of("agent-a"), reg.resolve("agent-a")); // agentId 直查兜底
        assertEquals(Optional.of("alice"), reg.nameOf("agent-a"));
        assertEquals(Optional.empty(), reg.resolve("ghost"));
    }

    @Test
    void 同名覆盖_旧id反查清除() {
        AgentNameRegistry reg = new AgentNameRegistry();
        reg.register("alice", "agent-1");
        reg.register("alice", "agent-2");
        assertEquals(Optional.of("agent-2"), reg.resolve("alice"));
        assertEquals(Optional.empty(), reg.resolve("agent-1"), "旧 agentId 反查应清除");
        assertEquals(Optional.of("alice"), reg.nameOf("agent-2"));
    }

    @Test
    void 同一agentId换名_旧名清除() {
        AgentNameRegistry reg = new AgentNameRegistry();
        reg.register("alice", "agent-1");
        reg.register("bob", "agent-1");
        assertEquals(Optional.empty(), reg.resolve("alice"));
        assertEquals(Optional.of("agent-1"), reg.resolve("bob"));
    }

    @Test
    void unregister() {
        AgentNameRegistry reg = new AgentNameRegistry();
        reg.register("alice", "agent-1");
        reg.unregister("alice");
        assertTrue(reg.resolve("alice").isEmpty());
        assertTrue(reg.nameOf("agent-1").isEmpty());
        assertTrue(reg.snapshot().isEmpty());
    }
}
