package com.cortex.tui;

import com.cortex.command.TeamAccessor;
import com.cortex.team.BackendFactory;
import com.cortex.team.Team;
import com.cortex.team.TeamManager;
import com.cortex.team.TeammateInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link TeamAccessor} 的 TUI 适配（T27/F59-F62）：转发 {@link TeamManager}。
 */
public final class TuiTeamAccessor implements TeamAccessor {

    private final TeamManager mgr;
    private final String cortexJar;

    public TuiTeamAccessor(TeamManager mgr, String cortexJar) {
        this.mgr = mgr;
        this.cortexJar = cortexJar;
    }

    @Override
    public List<String> list() {
        List<String> lines = new ArrayList<>();
        for (Team t : mgr.list()) {
            long active = t.members().stream().filter(TeammateInfo::active).count();
            lines.add(t.sanitizedName() + "  " + t.backend().wireValue() + "  "
                    + t.members().size() + " 成员  [" + active + "/" + t.members().size() + "] 活跃");
        }
        return lines;
    }

    @Override
    public List<String> info(String name) {
        Team t = mgr.get(name).orElse(null);
        if (t == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("团队: " + t.sanitizedName() + "（" + t.name() + "）  后端: " + t.backend().wireValue());
        lines.add("配置: " + t.configPath());
        for (TeammateInfo m : t.members()) {
            lines.add("  " + m.name() + "  " + m.agentId() + "  " + m.backendType().wireValue()
                    + "  " + (m.isActive() == null || m.isActive() ? "活跃" : "空闲")
                    + (m.worktreePath().isEmpty() ? "" : "  " + m.worktreePath()));
        }
        return lines;
    }

    @Override
    public void delete(String name, boolean force) throws Exception {
        mgr.delete(name, force);
    }

    @Override
    public void kill(String teamName, String member) throws Exception {
        Team t = mgr.get(teamName).orElseThrow(() -> new Exception("未找到团队: " + teamName));
        TeammateInfo m = t.memberByName(member)
                .orElseThrow(() -> new Exception("团队 " + teamName + " 中不存在成员: " + member));
        new BackendFactory(cortexJar, null).create(m.backendType()).kill(m.paneId(), m.agentId());
        t.removeMember(member);
    }

    @Override
    public String teamOfMember(String member) {
        for (Team t : mgr.list()) {
            if (t.memberByName(member).isPresent()) {
                return t.sanitizedName();
            }
        }
        return null;
    }
}
