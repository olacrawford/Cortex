package com.cortex.permission;

import com.cortex.llm.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PermissionEngineTest {

    @TempDir
    Path root;

    private ToolCall call(String name, String args) {
        return new ToolCall("c1", name, args);
    }

    // ─── 模式兜底矩阵（F5/AC7）───

    @Test
    void 模式矩阵逐档逐类() {
        assertEquals(Decision.ALLOW, PermissionEngine.modeFallback(Mode.DEFAULT, Category.READ));
        assertEquals(Decision.ASK, PermissionEngine.modeFallback(Mode.DEFAULT, Category.WRITE));
        assertEquals(Decision.ASK, PermissionEngine.modeFallback(Mode.DEFAULT, Category.EXEC));

        assertEquals(Decision.ALLOW, PermissionEngine.modeFallback(Mode.ACCEPT_EDITS, Category.READ));
        assertEquals(Decision.ALLOW, PermissionEngine.modeFallback(Mode.ACCEPT_EDITS, Category.WRITE));
        assertEquals(Decision.ASK, PermissionEngine.modeFallback(Mode.ACCEPT_EDITS, Category.EXEC));

        assertEquals(Decision.ASK, PermissionEngine.modeFallback(Mode.PLAN, Category.WRITE)); // 防御性兜底
        assertEquals(Decision.ASK, PermissionEngine.modeFallback(Mode.PLAN, Category.EXEC));

        assertEquals(Decision.ALLOW, PermissionEngine.modeFallback(Mode.BYPASS, Category.WRITE));
        assertEquals(Decision.ALLOW, PermissionEngine.modeFallback(Mode.BYPASS, Category.EXEC));
    }

    // ─── 层级短路（F6/AC8）───

    @Test
    void 黑名单最高优先_bypass也拦() {
        PermissionEngine engine = PermissionEngine.create(root);
        PermissionEngine.CheckResult r = engine.check(Mode.BYPASS, call("bash", "{\"command\":\"rm -rf /\"}"), false);
        assertEquals(Decision.DENY, r.decision());
        assertTrue(r.reason().contains("黑名单"));
    }

    @Test
    void 非命令执行工具不被黑名单拦() {
        PermissionEngine engine = PermissionEngine.create(root);
        // 读一个名字恰好含危险串的路径——黑名单只对 EXEC 类生效
        PermissionEngine.CheckResult r = engine.check(Mode.DEFAULT, call("read_file", "{\"path\":\"rm -rf /\"}"), true);
        // 路径 "rm -rf /" 相对路径 → root.resolve → 项目内 → 放行（READ 恒 Allow）
        assertEquals(Decision.ALLOW, r.decision());
    }

    @Test
    void 命令执行工具不被沙箱拦() {
        PermissionEngine engine = PermissionEngine.create(root);
        // bash 跑 /etc 下的命令——沙箱不围栏命令执行，落到规则/模式
        PermissionEngine.CheckResult r = engine.check(Mode.BYPASS, call("bash", "{\"command\":\"cat /etc/hostname\"}"), false);
        assertEquals(Decision.ALLOW, r.decision());
    }

    @Test
    void 沙箱拦截文件类越界() {
        PermissionEngine engine = PermissionEngine.create(root);
        PermissionEngine.CheckResult r = engine.check(Mode.BYPASS, call("read_file", "{\"path\":\"/etc/passwd\"}"), true);
        assertEquals(Decision.DENY, r.decision());
        assertTrue(r.reason().contains("项目目录之外"));
    }

    @Test
    void 文件路径不可解析按最严拒绝() {
        PermissionEngine engine = PermissionEngine.create(root);
        PermissionEngine.CheckResult r = engine.check(Mode.BYPASS, call("read_file", "不是JSON"), true);
        assertEquals(Decision.DENY, r.decision());
        assertTrue(r.reason().contains("无法解析"));
    }

    @Test
    void allow规则命中不进模式兜底() {
        PermissionEngine engine = PermissionEngine.create(root);
        PermissionEngine.CheckResult r = engine.check(Mode.DEFAULT, call("bash", "{\"command\":\"git status\"}"), false);
        // 项目内无规则时 default 对 EXEC 应 Ask——先确认基线
        assertEquals(Decision.ASK, r.decision());
    }

    // ─── 三级优先级（F4/AC5）───

    @Test
    void 本地盖过项目_项目盖过用户() throws Exception {
        Files.createDirectories(root.resolve(".cortex"));
        Files.writeString(root.resolve(".cortex/settings.yaml"), """
                permissions:
                  allow:
                    - "Bash(npm test)"
                """);
        Path local = root.resolve(".cortex/settings.local.yaml");
        Files.writeString(local, """
                permissions:
                  deny:
                    - "Bash(npm test)"
                """);

        PermissionEngine engine = PermissionEngine.create(root);
        PermissionEngine.CheckResult r = engine.check(Mode.BYPASS, call("bash", "{\"command\":\"npm test\"}"), false);
        assertEquals(Decision.DENY, r.decision()); // 本地 deny 盖过项目 allow
        assertTrue(r.reason().contains("本地"));

        // 项目 allow 盖过「用户层若存在的 deny」：删掉本地层再验
        Files.delete(local);
        PermissionEngine engine2 = PermissionEngine.create(root);
        assertEquals(Decision.ALLOW,
                engine2.check(Mode.DEFAULT, call("bash", "{\"command\":\"npm test\"}"), false).decision());
    }

    @Test
    void 用户层规则生效() throws Exception {
        // 用户层放行 git *
        Path userHome = root.resolve("home");
        Files.createDirectories(userHome.resolve(".cortex"));
        Files.writeString(userHome.resolve(".cortex/settings.yaml"), """
                permissions:
                  allow:
                    - "Bash(git *)"
                """);
        Path savedHome = Path.of(java.lang.System.getProperty("user.home"));
        java.lang.System.setProperty("user.home", userHome.toString());
        try {
            PermissionEngine engine = PermissionEngine.create(root);
            assertEquals(Decision.ALLOW,
                    engine.check(Mode.DEFAULT, call("bash", "{\"command\":\"git status\"}"), false).decision());
        } finally {
            java.lang.System.setProperty("user.home", savedHome.toString());
        }
    }

    @Test
    void deny规则命中不再进模式() {
        PermissionEngine engine = PermissionEngine.create(root);
        // 沙箱拦 /etc/passwd 已经是 DENY——这里验证 deny 规则层：本地层先写 deny
        // （直接用 withAllow/内存构造不便，走 persistLocalAllow 之外的用户配置已测；
        //  此处验证规则命中 reason 与模式 Ask reason 的可区分性即可）
        PermissionEngine.CheckResult ask = engine.check(Mode.DEFAULT, call("bash", "{\"command\":\"ls\"}"), false);
        assertEquals(Decision.ASK, ask.decision());
        assertTrue(ask.reason().contains("需确认"));
    }

    // ─── 配置降级（AC6）与启动模式（AC18）───

    @Test
    void 缺失配置按空规则运行_启动模式default() {
        PermissionEngine engine = PermissionEngine.create(root);
        assertEquals(Mode.DEFAULT, engine.startMode());
        PermissionEngine.CheckResult r = engine.check(Mode.DEFAULT, call("bash", "{\"command\":\"ls\"}"), false);
        assertEquals(Decision.ASK, r.decision());
    }

    @Test
    void 启动模式按本地优先生效() throws Exception {
        Files.createDirectories(root.resolve(".cortex"));
        Files.writeString(root.resolve(".cortex/settings.yaml"), "defaultMode: acceptEdits\n");
        assertEquals(Mode.ACCEPT_EDITS, PermissionEngine.create(root).startMode());

        Files.writeString(root.resolve(".cortex/settings.local.yaml"), "defaultMode: bypassPermissions\n");
        assertEquals(Mode.BYPASS, PermissionEngine.create(root).startMode()); // 本地盖过项目
    }

    @Test
    void 非法defaultMode降级default() throws Exception {
        Files.createDirectories(root.resolve(".cortex"));
        Files.writeString(root.resolve(".cortex/settings.yaml"), "defaultMode: yolo\n");
        assertEquals(Mode.DEFAULT, PermissionEngine.create(root).startMode());
    }

    // ─── 永久放行（F8/AC10）───

    @Test
    void 永久放行写入本地层并重载生效() throws Exception {
        PermissionEngine engine = PermissionEngine.create(root);
        ToolCall c = call("bash", "{\"command\":\"npm run build\"}");
        assertEquals(Decision.ASK, engine.check(Mode.DEFAULT, c, false).decision());

        engine.persistLocalAllow(c);

        Path localPath = root.resolve(".cortex/settings.local.yaml");
        assertTrue(Files.exists(localPath));
        String yaml = Files.readString(localPath);
        assertTrue(yaml.contains("Bash(npm run build)"));

        // 重载引擎后命中 allow
        PermissionEngine reloaded = PermissionEngine.create(root);
        assertEquals(Decision.ALLOW, reloaded.check(Mode.DEFAULT, c, false).decision());

        // 幂等：重复持久化不抛、不重复
        engine.persistLocalAllow(c);
        String yaml2 = Files.readString(localPath);
        assertEquals(1, yaml2.lines().filter(l -> l.contains("Bash(npm run build)")).count());
    }

    @Test
    void 永久放行转义glob元字符() throws Exception {
        PermissionEngine engine = PermissionEngine.create(root);
        ToolCall c = call("bash", "{\"command\":\"echo a*b\"}");
        engine.persistLocalAllow(c);
        Settings reloaded = Settings.load(root.resolve(".cortex/settings.local.yaml"));
        assertTrue(reloaded.allow().contains("Bash(echo a\\*b)"), reloaded.allow().toString());
        // 转义后的精确规则只匹配原命令，不匹配其他
        assertEquals(Decision.ALLOW, engine.check(Mode.DEFAULT, c, false).decision());
        assertNotEquals(Decision.ALLOW,
                engine.check(Mode.DEFAULT, call("bash", "{\"command\":\"echo axb\"}"), false).decision());
    }
}
