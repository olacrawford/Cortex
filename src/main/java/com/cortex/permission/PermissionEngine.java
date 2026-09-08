package com.cortex.permission;

import com.cortex.llm.ToolCall;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 权限引擎：五层防御的前四层（F6）——
 * ① 危险命令黑名单（仅命令执行类，不可绕过，N1）
 * ② 路径沙箱（仅文件类，先解析符号链接再前缀比对，N2）
 * ③ 规则引擎（本地 > 项目 > 用户，就近命中即止，同层 deny 优先）
 * ④ 模式兜底矩阵（只产 ALLOW/ASK，F5）
 * 返回 ASK 即「请走第五层人在回路」（由 agent 编排驱动，F8）。
 * 任一层给出 ALLOW/DENY 即短路返回；被跳过的层视为「未拦、继续」。
 */
public final class PermissionEngine {

    /** 判定结果：裁决 + 可读原因（Deny 回灌与 Ask 展示共用，F9）。 */
    public record CheckResult(Decision decision, String reason) {}

    private final Path root;
    private final List<Pattern> blacklist;
    private final RuleSet user;
    private final RuleSet project;
    private RuleSet local;
    private final Path localPath;
    private final Mode startMode;
    private final PrintStream warningSink;

    private PermissionEngine(Path root, List<Pattern> blacklist, RuleSet user, RuleSet project,
                             RuleSet local, Path localPath, Mode startMode, PrintStream warningSink) {
        this.root = root;
        this.blacklist = blacklist;
        this.user = user;
        this.project = project;
        this.local = local;
        this.localPath = localPath;
        this.startMode = startMode;
        this.warningSink = warningSink;
    }

    /**
     * 构造引擎：解析项目根、加载三层配置、确定启动默认模式。
     * 配置文件缺失/格式非法只降级为空规则（N5）；
     * 唯一致命错（项目根解析失败）也返回非 null 的空规则安全引擎，仅向 warningSink 打警告。
     */
    public static PermissionEngine create(Path root, PrintStream warningSink) {
        Path resolved;
        try {
            resolved = Sandbox.resolveRoot(root);
        } catch (IOException e) {
            warningSink.println("权限引擎降级（项目根解析失败）: " + e.getMessage());
            return new PermissionEngine(root.toAbsolutePath(), Blacklist.patterns(),
                    RuleSet.EMPTY, RuleSet.EMPTY, RuleSet.EMPTY,
                    root.toAbsolutePath().resolve(".cortex/settings.local.yaml"),
                    Mode.DEFAULT, warningSink);
        }
        Path userDir = Path.of(java.lang.System.getProperty("user.home"), ".cortex");
        Settings userSettings = Settings.load(userDir.resolve("settings.yaml"));
        Settings projectSettings = Settings.load(resolved.resolve(".cortex/settings.yaml"));
        Settings localSettings = Settings.load(resolved.resolve(".cortex/settings.local.yaml"));
        Path localPath = resolved.resolve(".cortex/settings.local.yaml");

        Mode startMode = Mode.DEFAULT;
        for (Settings s : List.of(localSettings, projectSettings, userSettings)) { // 越本地越优先
            Optional<Mode> parsed = Mode.parse(s.defaultMode());
            if (parsed.isPresent()) {
                startMode = parsed.get();
                break;
            }
        }
        return new PermissionEngine(resolved, Blacklist.patterns(),
                Settings.toRuleSet(userSettings), Settings.toRuleSet(projectSettings),
                Settings.toRuleSet(localSettings), localPath, startMode, warningSink);
    }

    public static PermissionEngine create(Path root) {
        return create(root, java.lang.System.err);
    }

    /**
     * 前四层判定流水线（F6，短路）。readOnly 由调用方按批类型给定（等价 registry.isReadOnly）。
     */
    public CheckResult check(Mode mode, ToolCall call, boolean readOnly) {
        Category cat = Settings.categorize(call.name(), readOnly);
        String friendly = Settings.friendlyName(call.name());
        Settings.TargetInfo ti = Settings.extractTarget(call);
        boolean isCommand = cat == Category.EXEC;

        // ① 黑名单：仅命令执行类且有命令串（最高优先级，N1——bypass 也拦）
        if (cat == Category.EXEC && ti.target() != null && !ti.target().isEmpty()) {
            if (Blacklist.hitsBlacklist(ti.target())) {
                return new CheckResult(Decision.DENY, "命中危险命令黑名单：" + abbreviate(ti.target()));
            }
        }

        // ② 沙箱：仅文件类；路径不可解析按最严拒绝；逃出项目根判 Deny（N2/N7）
        if (ti.isFile()) {
            if (!ti.ok()) {
                return new CheckResult(Decision.DENY, "无法解析文件路径参数，安全拒绝");
            }
            if (!Sandbox.sandboxOK(root, ti.target())) {
                return new CheckResult(Decision.DENY, "路径在项目目录之外：" + ti.target());
            }
        }

        // ③ 规则引擎：本地 > 项目 > 用户，就近命中即止（F4）
        List<RuleSet> layers = List.of(local, project, user);
        String[] layerNames = {"本地", "项目", "用户"};
        for (int i = 0; i < layers.size(); i++) {
            Optional<Decision> hit = layers.get(i).match(friendly, ti.target(), isCommand);
            if (hit.isPresent()) {
                String ruleText = friendly + (ti.target().isEmpty() ? "" : "(" + ti.target() + ")");
                return new CheckResult(hit.get(), layerNames[i] + "规则判定：" + ruleText);
            }
        }

        // ④ 模式兜底：只产 ALLOW 或 ASK（F5）
        Decision d = modeFallback(mode, cat);
        if (d == Decision.ALLOW) {
            return new CheckResult(Decision.ALLOW, "");
        }
        return new CheckResult(Decision.ASK,
                mode.displayName() + " 模式下 " + categoryName(cat) + " 类操作需确认");
    }

    /** 模式兜底矩阵（F5）：只读恒 ALLOW；bypass 全 ALLOW；acceptEdits 放行文件写；其余 ASK。 */
    public static Decision modeFallback(Mode mode, Category cat) {
        if (cat == Category.READ || mode == Mode.BYPASS) {
            return Decision.ALLOW;
        }
        if (mode == Mode.ACCEPT_EDITS && cat == Category.WRITE) {
            return Decision.ALLOW;
        }
        return Decision.ASK;
    }

    /** 人在回路「永久允许」：生成精确 allow 规则并写入本地层配置（F8）；同步并入内存规则集。 */
    public void persistLocalAllow(ToolCall call) throws IOException {
        Settings.TargetInfo ti = Settings.extractTarget(call);
        String friendly = Settings.friendlyName(call.name());
        String ruleText;
        if (!ti.isFile()) {
            if (ti.target() == null || ti.target().isEmpty()) {
                return; // 取不到命令串，无法生成精确规则
            }
            ruleText = friendly + "(" + Settings.escapeGlob(ti.target()) + ")";
        } else {
            if (!ti.ok()) {
                return;
            }
            ruleText = friendly + "(" + relativeSlashPath(ti.target()) + ")";
        }
        Settings merged = Settings.load(localPath).withAllowRule(ruleText);
        if (merged.allow().size() == Settings.load(localPath).allow().size()) {
            return; // 已存在，幂等不重写
        }
        if (localPath.getParent() != null) {
            Files.createDirectories(localPath.getParent());
        }
        Files.writeString(localPath, merged.toYaml());
        local = local.withAllow(new Rule(friendly,
                !ti.isFile() ? Settings.escapeGlob(ti.target()) : relativeSlashPath(ti.target()), true));
    }

    public Mode startMode() {
        return startMode;
    }

    private String relativeSlashPath(String target) {
        Path p = Path.of(target);
        Path abs = p.isAbsolute() ? p : root.resolve(p);
        Path rel;
        try {
            rel = root.relativize(abs);
        } catch (Exception e) {
            return target.replace('\\', '/');
        }
        String text = rel.toString().replace('\\', '/');
        return text.isEmpty() ? "." : text;
    }

    private static String categoryName(Category cat) {
        return switch (cat) {
            case READ -> "只读";
            case WRITE -> "文件写";
            case EXEC -> "命令执行";
        };
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }
}
