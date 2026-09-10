package com.cortex.team;

import com.cortex.agent.Agent;
import com.cortex.agent.SessionRuntime;
import com.cortex.conversation.ConversationManager;
import com.cortex.llm.LlmClient;
import com.cortex.llm.Request;
import com.cortex.llm.StreamEvent;
import com.cortex.permission.PermissionEngine;
import com.cortex.subagent.Catalog;
import com.cortex.task.Manager;
import com.cortex.tool.Filter;
import com.cortex.worktree.WorktreeManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 队员 spawn 全链路（T18/F25/AC7/AC17/AC25）+ in-process 空闲通知 + SendMessage 续写（T31/AC18）。 */
class SpawnTeammateTest {

    @TempDir
    Path tmp;

    private record Deps(TeamManager teamMgr, Manager taskMgr, AgentNameRegistry registry,
                        com.cortex.worktree.WorktreeManager wtMgr, Path repo) {}

    /** 脚本化客户端：每轮直接文本收尾。 */
    private static LlmClient textClient() {
        return req -> {
            LinkedBlockingQueue<StreamEvent> q = new LinkedBlockingQueue<>();
            q.add(new StreamEvent.TextDelta("队员完成"));
            q.add(new StreamEvent.StreamEnd("stop", 0, 0));
            return q;
        };
    }

    private Deps setup() throws Exception {
        Path repo = com.cortex.worktree.GitTestSupport.initRepo(tmp.resolve("repo-" + System.nanoTime()));
        Files.createDirectories(repo.resolve(".cortex/agents"));
        Files.writeString(repo.resolve(".cortex/agents/worker.md"), """
                ---
                name: worker
                description: 测试队员
                maxTurns: 6
                ---
                你是 worker 队员。
                """);
        WorktreeManager wtMgr = new WorktreeManager(repo);
        Manager taskMgr = new Manager();
        AgentNameRegistry registry = new AgentNameRegistry();
        taskMgr.setNameRegistry(registry);
        Catalog catalog = Catalog.load(repo);
        TeamManager teamMgr = new TeamManager(tmp.resolve("home-" + System.nanoTime()), repo,
                wtMgr, taskMgr, registry, catalog, "cortex.jar");
        // LeadEnv：脚本化客户端——队员第 1 轮直接文本收尾
        teamMgr.setLeadEnv(new TeamManager.LeadEnv(textClient(), new com.cortex.tool.ToolRegistry(),
                "test", PermissionEngine.create(repo), 200000, repo, null));
        return new Deps(teamMgr, taskMgr, registry, wtMgr, repo);
    }

    @Test
    void spawn队员_worktree隔离_空闲通知_续写() throws Exception {
        Deps d = setup();
        d.teamMgr().create("demo", "", BackendType.IN_PROCESS);

        String json = d.teamMgr().spawnTeammate(new com.cortex.agent.TeamHook.TeamSpawnRequest(
                "demo", "随便做点事收个尾", "alice", "worker", null, null));
        assertTrue(json.contains("\"memberName\":\"alice\""), json);
        assertTrue(json.contains("\"backend\":\"in-process\""), json);

        // AC7：worktree 落地 team-demo+alice
        Path wt = d.repo().resolve(".cortex/worktrees/team-demo+alice");
        assertTrue(Files.isDirectory(wt), "AC7：嵌套 slug worktree 应落地");

        // 注册表寻址（F37）
        assertEquals(d.teamMgr().get("demo").orElseThrow()
                .memberByName("alice").orElseThrow().agentId(), d.registry().resolve("alice").orElseThrow());

        // 等队员跑完（AC17）：isActive=false + Lead 邮箱 idle
        String aliceId = d.registry().resolve("alice").orElseThrow();
        long deadline = System.currentTimeMillis() + 15000;
        while (d.taskMgr().get(aliceId).map(t -> t.status() == com.cortex.task.Status.RUNNING)
                .orElse(true) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        d.teamMgr().handleTaskDone(aliceId);
        Team team = d.teamMgr().get("demo").orElseThrow();
        assertFalse(team.memberByName("alice").orElseThrow().active(), "AC17：空闲后 isActive=false");
        Mailbox mailbox = new Mailbox(team.mailboxDir());
        var leadUnread = mailbox.readUnread("lead");
        assertTrue(leadUnread.messages().stream().anyMatch(m -> m.summary().contains("alice idle")),
                "AC17：Lead 邮箱收到 idle 消息");

        // AC18：SendMessage 续写——从 COMPLETED 回 RUNNING
        d.taskMgr().sendMessage("alice", "再做一件事");
        assertEquals(com.cortex.task.Status.RUNNING, d.taskMgr().get(aliceId).orElseThrow().status());
    }

    @Test
    void 未知角色spawn报错_重名报错() throws Exception {
        Deps d = setup();
        d.teamMgr().create("demo", "", BackendType.IN_PROCESS);
        var e = assertThrows(TeamException.class,
                () -> d.teamMgr().spawnTeammate(new com.cortex.agent.TeamHook.TeamSpawnRequest(
                        "demo", "任务", "eve", "ghost-type", null, null)));
        assertTrue(e.getMessage().contains("未知 subagent_type"));
        d.teamMgr().spawnTeammate(new com.cortex.agent.TeamHook.TeamSpawnRequest(
                "demo", "任务", "alice", "worker", null, null));
        var e2 = assertThrows(MemberExistsException.class,
                () -> d.teamMgr().spawnTeammate(new com.cortex.agent.TeamHook.TeamSpawnRequest(
                        "demo", "任务", "alice", "worker", null, null)));
        assertTrue(e2.getMessage().contains("alice"));
    }

    @Test
    void pane后端spawn被拒() throws Exception {
        Deps d = setup();
        Team t = d.teamMgr().create("pane-demo", "", BackendType.TMUX);
        TeamException e = assertThrows(TeamException.class,
                () -> d.teamMgr().spawnTeammate(new com.cortex.agent.TeamHook.TeamSpawnRequest(
                        t.sanitizedName(), "任务", "carol", "worker", null, null)));
        assertTrue(e.getMessage().contains("未实现"));
    }

    @Test
    void worktree缺失时spawn报错() throws Exception {
        Path home = tmp.resolve("home-nowt");
        Manager taskMgr = new Manager();
        TeamManager teamMgr = new TeamManager(home, tmp, null, taskMgr,
                new AgentNameRegistry(), Catalog.load(tmp), "cortex.jar");
        teamMgr.setLeadEnv(new TeamManager.LeadEnv(
                req -> new LinkedBlockingQueue<>(), new com.cortex.tool.ToolRegistry(),
                "t", PermissionEngine.create(tmp), 200000, tmp, null));
        teamMgr.create("demo", "", BackendType.IN_PROCESS);
        TeamException e = assertThrows(TeamException.class,
                () -> teamMgr.spawnTeammate(new com.cortex.agent.TeamHook.TeamSpawnRequest(
                        "demo", "任务", "dave", "general-purpose", null, null)));
        assertTrue(e.getMessage().contains("Worktree 管理器未启用"));
    }
}
