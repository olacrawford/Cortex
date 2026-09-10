package com.cortex.team;

import com.cortex.tool.Result;
import com.cortex.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * TeamDelete（F22/F23/G4）：全员空闲才可删（force 强制）；删队员 worktree/session/team 目录。
 */
public final class TeamDeleteTool implements Tool {

    private final TeamManager manager;

    public TeamDeleteTool(TeamManager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TeamDelete";
    }

    @Override
    public String description() {
        return "删除团队：清掉队员 worktree、session 与团队配置；仍有活跃队员时拒绝（可 force 强制）。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "teamName", Map.of("type", "string", "description", "团队名"),
                        "force", Map.of("type", "boolean", "description", "忽略活跃成员强制删除")),
                "required", List.of("teamName"));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(String argsJson) {
        String teamName = TaskArg.stringArg(argsJson, "teamName");
        if (teamName == null || teamName.isBlank()) {
            return Result.error("缺少必填参数 teamName");
        }
        boolean force = TaskArg.boolArg(argsJson, "force");
        try {
            manager.delete(teamName.strip(), force);
            return Result.ok("{\"teamName\":\"" + teamName.strip() + "\",\"status\":\"deleted\"}");
        } catch (Exception e) {
            return Result.error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}
