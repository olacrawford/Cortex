package com.cortex.instructions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * 项目指令文件加载器（F1~F6）：按优先级扫描三层 MEWCODE.md 并展开 {@code @include} 引用，
 * 高优先级在前、空行分隔。@include 支持嵌套深度限制、环路检测、路径逃逸检测与二进制跳过。
 */
public final class Loader {

    /** 指令文件名（三层共用）。 */
    public static final String FILE_NAME = "MEWCODE.md";
    static final int DEFAULT_MAX_DEPTH = 5;

    private final Path projectRoot;
    private final Path userHome;
    private final int maxDepth;

    public Loader(Path projectRoot) {
        this(projectRoot, Path.of(System.getProperty("user.home")), DEFAULT_MAX_DEPTH);
    }

    Loader(Path projectRoot, Path userHome, int maxDepth) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.userHome = userHome.toAbsolutePath().normalize();
        this.maxDepth = maxDepth;
    }

    /** 按优先级（项目根 → 项目配置 → 用户级）加载并展开，高优先级在前、空行分隔；跳过空段。 */
    public String load() {
        Path rootFile = projectRoot.resolve(FILE_NAME);
        Path projCfgFile = projectRoot.resolve(".cortex").resolve(FILE_NAME);
        Path userFile = userHome.resolve(".cortex").resolve(FILE_NAME);
        String a = loadFile(rootFile, projectRoot, 1, new HashSet<>());
        String b = loadFile(projCfgFile, projectRoot, 1, new HashSet<>());
        String c = loadFile(userFile, userHome.resolve(".cortex"), 1, new HashSet<>());
        StringBuilder sb = new StringBuilder();
        for (String s : new String[]{a, b, c}) {
            if (s != null && !s.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(s);
            }
        }
        return sb.toString();
    }

    /**
     * 展开单个指令文件：深度限制 → 环路检测 → 逃逸检测 → 二进制检测 → @include 行递归展开。
     * 返回展开后的完整内容；缺失 / 不可读返回空串（非错误）。
     */
    private String loadFile(Path file, Path boundary, int depth, Set<Path> visited) {
        if (depth > maxDepth) {
            return "<!-- @include 超过最大嵌套深度，已跳过: " + file + " -->";
        }
        Path absolute;
        try {
            absolute = file.toRealPath();
        } catch (IOException e) {
            return ""; // 缺失 / 不可读：静默跳过
        }
        if (!absolute.startsWith(boundary)) {
            return "<!-- @include 路径超出允许范围，已跳过: " + file + " -->";
        }
        if (!visited.add(absolute)) {
            return "<!-- @include 检测到环路，已跳过: " + file + " -->";
        }
        try {
            if (isBinary(absolute)) {
                return "<!-- @include 二进制文件已跳过: " + file + " -->";
            }
            String content = Files.readString(absolute);
            String[] lines = content.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                if (line.startsWith("@include ")) {
                    String rel = line.substring("@include ".length()).trim();
                    if (rel.isEmpty()) {
                        sb.append(line).append('\n');
                        continue;
                    }
                    Path included = absolute.getParent().resolve(rel);
                    sb.append(loadFile(included, boundary, depth + 1, visited)).append('\n');
                } else {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        } finally {
            visited.remove(absolute);
        }
    }

    /** 前 512 字节包含 {@code \x00} 视为二进制不可读。 */
    private static boolean isBinary(Path file) throws IOException {
        byte[] buf = new byte[512];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.read(buf);
        }
        for (int i = 0; i < n; i++) {
            if (buf[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
