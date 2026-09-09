package com.cortex.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SkillInstallerTest {

    @TempDir
    Path tmp;

    // ─── URL 解析 ───

    @Test
    void parseSkillURL三种形态() {
        SkillInstaller.RepoRef sh = SkillInstaller.parseSkillURL("https://skills.sh/acme/demo-skill");
        assertEquals("acme", sh.owner());
        assertEquals("demo-skill", sh.repo());
        assertNull(sh.ref());
        assertFalse(sh.singleFile());

        SkillInstaller.RepoRef tree = SkillInstaller.parseSkillURL(
                "https://github.com/acme/demo-skill/tree/main/skills/commit");
        assertEquals("acme", tree.owner());
        assertEquals("demo-skill", tree.repo());
        assertEquals("main", tree.ref());
        assertEquals("skills/commit", tree.path());
        assertFalse(tree.singleFile());

        SkillInstaller.RepoRef raw = SkillInstaller.parseSkillURL(
                "https://raw.githubusercontent.com/acme/demo-skill/main/SKILL.md");
        assertEquals("acme", raw.owner());
        assertEquals("demo-skill", raw.repo());
        assertEquals("main", raw.ref());
        assertEquals("SKILL.md", raw.path());
        assertTrue(raw.singleFile());
    }

    @Test
    void parseSkillURL非法形态抛异常() {
        assertThrows(IllegalArgumentException.class, () -> SkillInstaller.parseSkillURL(null));
        assertThrows(IllegalArgumentException.class, () -> SkillInstaller.parseSkillURL(""));
        assertThrows(IllegalArgumentException.class, () -> SkillInstaller.parseSkillURL("https://example.com/x"));
        assertThrows(IllegalArgumentException.class, () -> SkillInstaller.parseSkillURL("https://github.com/acme"));
        assertThrows(IllegalArgumentException.class, () -> SkillInstaller.parseSkillURL("https://skills.sh/acme"));
    }

    // ─── 安装 ───

    @Test
    void install_目录树原子落位_暂存清理() throws IOException {
        Map<String, byte[]> net = new HashMap<>();
        net.put("https://api.github.com/repos/acme/demo-skill/contents/my-skill?ref=main",
                filesJson(
                        file("SKILL.md", "https://dl/SKILL.md"),
                        dir("refs", "https://api.github.com/repos/acme/demo-skill/contents/my-skill/refs?ref=main")));
        net.put("https://api.github.com/repos/acme/demo-skill/contents/my-skill/refs?ref=main",
                filesJson(file("extra.md", "https://dl/extra.md")));
        net.put("https://dl/SKILL.md", "---\nname: my-skill\ndescription: 安装测试\n---\n正文".getBytes());
        net.put("https://dl/extra.md", "参考资料".getBytes());

        Path root = tmp.resolve("skills-root");
        String name = SkillInstaller.install(net::get,
                SkillInstaller.parseSkillURL("https://github.com/acme/demo-skill/tree/main/my-skill"), root);

        assertEquals("my-skill", name);
        assertTrue(Files.isRegularFile(root.resolve("my-skill/SKILL.md")));
        assertTrue(Files.isRegularFile(root.resolve("my-skill/refs/extra.md")));
        // 暂存目录已清理（finally 分支）
        try (Stream<Path> list = Files.list(root)) {
            assertEquals(1, list.count(), "安装后 root 下只应有最终技能目录");
        }
    }

    @Test
    void install_单文件raw形态() throws IOException {
        Map<String, byte[]> net = new HashMap<>();
        net.put("https://raw.githubusercontent.com/acme/demo-skill/main/SKILL.md",
                "---\nname: raw-skill\ndescription: 单文件\n---\n正文".getBytes());

        Path root = tmp.resolve("skills-root");
        String name = SkillInstaller.install(net::get,
                SkillInstaller.parseSkillURL("https://raw.githubusercontent.com/acme/demo-skill/main/SKILL.md"), root);
        assertEquals("demo-skill", name, "单文件安装目录名取 repo 名");
        assertTrue(Files.isRegularFile(root.resolve("demo-skill/SKILL.md")));
    }

    @Test
    void install_缺SKILLmd拒绝安装并清理暂存() {
        Map<String, byte[]> net = new HashMap<>();
        net.put("https://api.github.com/repos/acme/bad/contents/my-skill?ref=main",
                filesJson(file("README.md", "https://dl/README.md")));
        net.put("https://dl/README.md", "不是技能".getBytes());

        Path root = tmp.resolve("skills-root");
        IOException ex = assertThrows(IOException.class, () -> SkillInstaller.install(net::get,
                SkillInstaller.parseSkillURL("https://github.com/acme/bad/tree/main/my-skill"), root));
        assertTrue(ex.getMessage().contains("SKILL.md"));
        assertFalse(Files.exists(root.resolve("my-skill")), "拒绝安装不得落位");
    }

    @Test
    void install_限额_单文件超限() {
        Map<String, byte[]> net = new HashMap<>();
        net.put("https://api.github.com/repos/acme/big/contents/my-skill?ref=main",
                filesJson(file("SKILL.md", "https://dl/SKILL.md")));
        net.put("https://dl/SKILL.md", new byte[(int) (SkillInstaller.MAX_FILE_SIZE + 1)]);

        Path root = tmp.resolve("skills-root");
        assertThrows(IOException.class, () -> SkillInstaller.install(net::get,
                SkillInstaller.parseSkillURL("https://github.com/acme/big/tree/main/my-skill"), root));
    }

    @Test
    void install_限额_文件数超限() {
        SkillInstaller.RepoRef ref = new SkillInstaller.RepoRef("acme", "many", "main", "my-skill", false);
        StringBuilder json = new StringBuilder("[");
        Map<String, byte[]> net = new HashMap<>();
        for (int i = 0; i <= SkillInstaller.MAX_FILE_COUNT; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"type\":\"file\",\"name\":\"f").append(i).append(".md\",\"download_url\":\"https://dl/f")
                    .append(i).append("\"}");
            net.put("https://dl/f" + i, "x".getBytes());
        }
        json.append("]");
        net.put("https://api.github.com/repos/acme/many/contents/my-skill?ref=main", json.toString().getBytes());

        Path root = tmp.resolve("skills-root");
        assertThrows(IOException.class, () -> SkillInstaller.install(net::get, ref, root));
    }

    @Test
    void install_限额_目录深度超限() {
        // 连续 5 层目录（depth 0..4 之内允许，第 5 层拒绝）
        String api = "https://api.github.com/repos/acme/deep/contents/my-skill?ref=main";
        Map<String, byte[]> net = new HashMap<>(Map.of(api, filesJson(dir("d1",
                "https://api.github.com/repos/acme/deep/contents/my-skill/d1?ref=main"))));
        String prefix = "my-skill";
        for (int i = 1; i <= SkillInstaller.MAX_RECURSION_DEPTH + 1; i++) {
            String parentUrl = "https://api.github.com/repos/acme/deep/contents/" + prefix + "?ref=main";
            prefix = prefix + "/d" + i;
            String childUrl = "https://api.github.com/repos/acme/deep/contents/" + prefix + "?ref=main";
            net.put(parentUrl, filesJson(dir("d" + i, childUrl)));
        }
        Path root = tmp.resolve("skills-root");
        assertThrows(IOException.class, () -> SkillInstaller.install(net::get,
                new SkillInstaller.RepoRef("acme", "deep", "main", "my-skill", false), root));
    }

    @Test
    void install_目标已存在拒绝() throws IOException {
        Map<String, byte[]> net = new HashMap<>();
        net.put("https://api.github.com/repos/acme/demo-skill/contents/my-skill?ref=main",
                filesJson(file("SKILL.md", "https://dl/SKILL.md")));
        net.put("https://dl/SKILL.md", "正文".getBytes());
        Path root = tmp.resolve("skills-root");
        Files.createDirectories(root.resolve("my-skill"));

        assertThrows(IOException.class, () -> SkillInstaller.install(net::get,
                SkillInstaller.parseSkillURL("https://github.com/acme/demo-skill/tree/main/my-skill"), root));
    }

    // ─── JSON 辅助 ───

    private static byte[] filesJson(Object... entries) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(entries[i]);
        }
        return sb.append("]").toString().getBytes();
    }

    private static String file(String name, String downloadUrl) {
        return "{\"type\":\"file\",\"name\":\"" + name + "\",\"download_url\":\"" + downloadUrl + "\"}";
    }

    private static String dir(String name, String apiUrl) {
        return "{\"type\":\"dir\",\"name\":\"" + name + "\"}";
    }
}
