package com.cortex.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** TeamManager（T3/T3b/AC1-AC5）：sanitize、同名后缀、删除保护、跨进程 reload。 */
class TeamManagerTest {

    @TempDir
    Path tmp;

    private TeamManager mgr() throws Exception {
        return new TeamManager(tmp.resolve("home-" + System.nanoTime()), tmp, null, null,
                new AgentNameRegistry(), null, "cortex.jar");
    }

    @Test
    void 构造创建teams目录() throws Exception {
        Path home = tmp.resolve("home1");
        new TeamManager(home, tmp, null, null, new AgentNameRegistry(), null, "cortex.jar");
        assertTrue(Files.isDirectory(home.resolve(".cortex/teams")));
    }

    @Test
    void 启动扫描还原已有团队() throws Exception {
        Path home = tmp.resolve("home2");
        TeamManager first = new TeamManager(home, tmp, null, null, new AgentNameRegistry(), null, "cortex.jar");
        first.create("demo", "", BackendType.IN_PROCESS);
        TeamManager second = new TeamManager(home, tmp, null, null, new AgentNameRegistry(), null, "cortex.jar");
        assertTrue(second.get("demo").isPresent(), "AC1：启动扫描还原 teams map");
        assertEquals(1, second.list().size());
    }

    @Test
    void sanitize与目录落地() throws Exception {
        TeamManager mgr = mgr();
        Team t = mgr.create("refactor auth", "");
        assertEquals("refactor-auth", t.sanitizedName());
        assertTrue(Files.exists(t.configPath()), "AC2：config.json 落地");
        Persistence.TeamSnapshot snap = Persistence.readJson(t.configPath(), Persistence.TeamSnapshot.class).orElseThrow();
        assertEquals("lead", snap.leadAgentId());
        assertNotNull(snap.backend());
    }

    @Test
    void 同名团队自动后缀() throws Exception {
        TeamManager mgr = mgr();
        Team t1 = mgr.create("demo", "");
        Team t2 = mgr.create("demo", "");
        assertEquals("demo", t1.sanitizedName());
        assertEquals("demo-2", t2.sanitizedName()); // AC3
        assertEquals(2, mgr.list().size());
    }

    @Test
    void 坏团队目录启动时跳过() throws Exception {
        Path home = tmp.resolve("home3");
        Files.createDirectories(home.resolve(".cortex/teams/broken"));
        Files.writeString(home.resolve(".cortex/teams/broken/config.json"), "{bad");
        TeamManager mgr = new TeamManager(home, tmp, null, null, new AgentNameRegistry(), null, "cortex.jar");
        assertTrue(mgr.list().isEmpty(), "F64：解析失败目录跳过不阻断");
    }

    @Test
    void delete有活跃成员拒绝_force放行() throws Exception {
        Path home = tmp.resolve("home4");
        TeamManager mgr = new TeamManager(home, tmp, null, null, new AgentNameRegistry(), null, "cortex.jar");
        Team t = mgr.create("demo", "", BackendType.IN_PROCESS);
        t.addMember(new TeammateInfo("alice", "agent-x", "general-purpose", "",
                "", "", BackendType.IN_PROCESS, "", true, false, ""));

        assertThrows(TeamHasActiveMembersException.class, () -> mgr.delete("demo", false), "AC4");
        assertTrue(Files.exists(t.configDir()), "拒绝删除后目录仍在");

        mgr.delete("demo", true); // AC5
        assertFalse(Files.exists(t.configDir()));
        assertTrue(mgr.get("demo").isEmpty());
    }
}
