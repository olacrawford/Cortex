package com.cortex.skill;

import com.cortex.tool.Result;
import com.cortex.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 远程技能安装工具（F13）：用户把 URL 发给 Agent，由模型调用本工具把技能装到用户全局层
 * {@code ~/.cortex/skills/}，装完自动 reload catalog 并回调重新注册斜杠命令，无需重启。
 * 写盘 + 网络 → {@code readOnly() = false}（write 类，受权限模式约束，DEFAULT 下走 Ask）。
 */
public final class InstallSkillTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillCatalog catalog;
    private final Path workDir;
    private final Path installRoot;
    private final Runnable onInstalled;

    public InstallSkillTool(SkillCatalog catalog, Path workDir, Path installRoot, Runnable onInstalled) {
        this.catalog = catalog;
        this.workDir = workDir;
        this.installRoot = installRoot;
        this.onInstalled = onInstalled;
    }

    @Override
    public String name() {
        return "install_skill";
    }

    @Override
    public String description() {
        return "安装远程技能。支持三种 URL：https://skills.sh/<owner>/<repo>、"
                + "https://github.com/<owner>/<repo>/tree/<ref>[/<path>]、"
                + "https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>/SKILL.md。"
                + "安装成功后技能立即可用（自动注册为斜杠命令），无需重启。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of("type", "string", "description", "技能来源 URL（skills.sh / github tree / raw SKILL.md）")),
                "required", List.of("url"));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(String argsJson) {
        try {
            JsonNode args = MAPPER.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            String url = args.path("url").asText("");
            if (url.isBlank()) {
                return Result.error("缺少 url 参数");
            }
            SkillInstaller.RepoRef ref = SkillInstaller.parseSkillURL(url);
            String dirName = SkillInstaller.install(SkillInstaller.defaultFetcher(), ref, installRoot);
            catalog.reload(workDir);
            if (onInstalled != null) {
                onInstalled.run();
            }
            return Result.ok("已安装技能 " + dirName + " 到 " + installRoot + "，斜杠命令已就绪。");
        } catch (Exception e) {
            return Result.error("安装失败: " + e.getMessage());
        }
    }
}
