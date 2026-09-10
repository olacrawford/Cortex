package com.cortex.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Team 成员操作（T3b/T4/F8-F10/F19c）：reload-before-modify 跨进程兜底。 */
class TeamMemberTest {

    @TempDir
    Path tmp;

    private Team newTeam(Path dir) throws Exception {
        Files.createDirectories(dir);
        Team t = new Team("demo", "demo", "lead", BackendType.IN_PROCESS, "", Instant.EPOCH, dir);
        return t;
    }

    @Test
    void add_setActive_remove往返并持久化() throws Exception {
        Path dir = tmp.resolve("t1");
        Team t = newTeam(dir);
        t.addMember(new TeammateInfo("alice", "agent-a1", "worker", "",
                "/wt", "wt-branch", BackendType.IN_PROCESS, "", null, false, "/session"));
        t.setMemberActive("alice", false);

        // 磁盘形态校验
        Persistence.TeamSnapshot snap = Persistence.readJson(t.configPath(), Persistence.TeamSnapshot.class).orElseThrow();
        assertEquals(1, snap.members().size());
        assertFalse(snap.members().get(0).isActive());
        assertEquals("agent-a1", snap.members().get(0).agentId());

        t.removeMember("alice");
        assertTrue(snap.members().size() >= 0); // snap 是旧快照
        assertTrue(t.members().isEmpty());
    }

    @Test
    void 重名成员抛异常() throws Exception {
        Team t = newTeam(tmp.resolve("t2"));
        t.addMember(new TeammateInfo("alice", "agent-1", "worker", "", "", "",
                BackendType.IN_PROCESS, "", null, false, ""));
        assertThrows(MemberExistsException.class,
                () -> t.addMember(new TeammateInfo("alice", "agent-2", "worker", "", "", "",
                        BackendType.IN_PROCESS, "", null, false, "")));
    }

    @Test
    void setMemberActive不存在的成员抛异常() {
        assertThrows(MemberNotFoundException.class, () -> newTeam(tmp.resolve("t3")).setMemberActive("ghost", false));
    }

    @Test
    void 跨进程reload_磁盘新成员对内存对象可见() throws Exception {
        // t3b：内存 Team 无 alice；另一进程写入带 alice 的 config；setMemberActive 应走 reload 成功（F19c）
        Path dir = tmp.resolve("t4");
        Team memoryTeam = newTeam(dir);
        memoryTeam.addMember(new TeammateInfo("bob", "agent-b", "worker", "", "", "",
                BackendType.IN_PROCESS, "", null, false, ""));

        // 模拟另一进程直接写磁盘（含 alice）
        Persistence.atomicWriteJson(dir.resolve("config.json"), new Persistence.TeamSnapshot(
                "demo", "demo", "lead", BackendType.IN_PROCESS, "", 0,
                List.of(new TeammateInfo("bob", "agent-b", "worker", "", "", "",
                                BackendType.IN_PROCESS, "", null, false, ""),
                        new TeammateInfo("alice", "agent-a", "worker", "", "", "",
                                BackendType.IN_PROCESS, "", null, false, ""))));

        memoryTeam.setMemberActive("alice", false); // reload 路径：不应抛 MemberNotFound
        Persistence.TeamSnapshot snap = Persistence.readJson(
                dir.resolve("config.json"), Persistence.TeamSnapshot.class).orElseThrow();
        assertFalse(snap.members().stream().filter(m -> m.name().equals("alice"))
                .findFirst().orElseThrow().isActive());
    }
}
