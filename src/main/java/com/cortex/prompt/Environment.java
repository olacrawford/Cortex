package com.cortex.prompt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * 运行环境信息（F2）：作为系统提示的独立第二段呈现，自身不进缓存（F3）。
 * 采集快速且有界（N4）；任一项取不到即降级留空（AC13）；不读任何环境变量（N5）。
 */
public record Environment(
        String workingDir,
        String platform,
        String date,
        String gitStatus,
        String version,
        String model) {

    public static Environment gather(String version, String model) {
        return new Environment(
                prop("user.dir"),
                prop("os.name"),
                LocalDate.now().toString(),
                gitStatus(Path.of(prop("user.dir"))),
                version == null ? "" : version,
                model == null ? "" : model);
    }

    private static String prop(String key) {
        String v = java.lang.System.getProperty(key);
        return v == null ? "" : v;
    }

    /**
     * `git status --porcelain` 摘要：clean → 「无未提交改动」；有输出 → 「N 个文件改动」；
     * 非 git 目录 / 超时 / 失败 → 空串（装配时省略）。
     */
    static String gitStatus(Path dir) {
        try {
            Process p = new ProcessBuilder("git", "status", "--porcelain")
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            if (p.exitValue() != 0) {
                return "";
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (out.isEmpty()) {
                return "无未提交改动";
            }
            return out.split("\n").length + " 个文件改动";
        } catch (Exception e) {
            return "";
        }
    }

    /** 渲染为「环境信息」段：逐行 Key: Value，空值项省略。 */
    public String render() {
        StringBuilder sb = new StringBuilder("## 环境信息\n");
        append(sb, "工作目录", workingDir);
        append(sb, "平台", platform);
        append(sb, "日期", date);
        append(sb, "git 状态", gitStatus);
        append(sb, "应用版本", version);
        append(sb, "模型", model);
        return sb.toString().stripTrailing();
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append("- ").append(key).append(": ").append(value).append('\n');
        }
    }
}
