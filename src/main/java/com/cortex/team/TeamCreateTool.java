package com.cortex.team;

import com.cortex.tool.Result;
import com.cortex.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * TeamCreate（F20/F21/G2）：主 Agent 创建 Team（Lead 注册为第一个成员），
 * 返回 sanitized 名、检测到的后端与配置路径。
 */
public final class TeamCreateTool implements Tool {

    private final TeamManager manager;

    public TeamCreateTool(TeamManager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TeamCreate";
    }

    @Override
    public String description() {
        return "创建一个长期团队（自己成为 Lead），之后用 Agent 工具带 teamName 参数往团队派队员协作。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "teamName", Map.of("type", "string", "description", "团队名（自动清洗为路径安全名，重名自动 -2/-3）"),
                        "description", Map.of("type", "string", "description", "团队描述（可选）"),
                        "agentType", Map.of("type", "string", "description", "保留位（本期不使用）")),
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
        String description = TaskArg.stringArg(argsJson, "description");
        try {
            Team team = manager.create(teamName.strip(), description);
            return Result.ok("{\"teamName\":\"" + team.sanitizedName()
                    + "\",\"backend\":\"" + team.backend().wireValue()
                    + "\",\"configPath\":\"" + team.configPath().toString().replace("\\", "\\\\") + "\"}");
        } catch (Exception e) {
            return Result.error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}
