package com.cortex.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 远程技能安装（F13）：解析 skills.sh / github.com tree / raw.githubusercontent.com 三种 URL，
 * 走 GitHub Contents API 递归拉取目录树（无需本地 git），限额内下载到兄弟暂存目录，
 * 校验含 SKILL.md 后原子 rename 到位；任何失败清理暂存不留残骸。
 * 网络经 {@link Fetcher} 抽象，测试注入桩实现，不打真实网络。
 */
public final class SkillInstaller {

    public static final long MAX_FILE_SIZE = 1024L * 1024;        // 单文件 ≤ 1 MiB
    public static final long MAX_TOTAL_SIZE = 8L * 1024 * 1024;   // 总大小 ≤ 8 MiB
    public static final int MAX_FILE_COUNT = 64;                  // 文件数 ≤ 64
    public static final int MAX_RECURSION_DEPTH = 4;              // 目录深度 ≤ 4

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 归一化后的远端仓库引用。 */
    public record RepoRef(String owner, String repo, String ref, String path, boolean singleFile) {}

    /** HTTP 抓取抽象：GET 指定 URL 返回 body；非 2xx 抛 IOException。 */
    @FunctionalInterface
    public interface Fetcher {
        byte[] fetch(String url) throws IOException;
    }

    private SkillInstaller() {}

    /** 默认抓取器：JDK HttpClient，60s 超时。 */
    public static Fetcher defaultFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(60))
                .build();
        return url -> {
            HttpResponse<byte[]> resp;
            try {
                resp = client.send(HttpRequest.newBuilder(URI.create(url))
                                .timeout(java.time.Duration.ofSeconds(60))
                                .header("Accept", "application/vnd.github+json")
                                .header("User-Agent", "cortex-skill-installer")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("下载被中断: " + url, e);
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IOException("HTTP " + resp.statusCode() + ": " + url);
            }
            return resp.body();
        };
    }

    /**
     * 解析三种 URL 形态；其它形态抛 {@link IllegalArgumentException}：
     * <ul>
     *   <li>{@code https://skills.sh/<owner>/<repo>} → 整仓默认分支</li>
     *   <li>{@code https://github.com/<owner>/<repo>/tree/<ref>[/<path>]} → 指定分支子目录</li>
     *   <li>{@code https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>/SKILL.md} → 单文件安装</li>
     * </ul>
     */
    public static RepoRef parseSkillURL(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("技能 URL 为空");
        }
        String u = url.strip();
        if (u.startsWith("https://skills.sh/")) {
            String[] parts = u.substring("https://skills.sh/".length()).split("/");
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("skills.sh URL 需形如 https://skills.sh/<owner>/<repo>: " + url);
            }
            return new RepoRef(parts[0], parts[1], null, "", false);
        }
        if (u.startsWith("https://github.com/")) {
            String rest = u.substring("https://github.com/".length());
            String[] parts = rest.split("/");
            // owner/repo/tree/ref[/path...]
            if (parts.length >= 5 && "tree".equals(parts[2])) {
                String path = joinPath(parts, 4);
                return new RepoRef(parts[0], parts[1], parts[3], path, false);
            }
            throw new IllegalArgumentException("github URL 需形如 https://github.com/<owner>/<repo>/tree/<ref>[/<path>]: " + url);
        }
        if (u.startsWith("https://raw.githubusercontent.com/")) {
            String rest = u.substring("https://raw.githubusercontent.com/".length());
            String[] parts = rest.split("/");
            // owner/repo/ref/path.../SKILL.md（path 至少一段文件名）
            if (parts.length >= 4) {
                String path = joinPath(parts, 3);
                return new RepoRef(parts[0], parts[1], parts[2], path, path.endsWith("SKILL.md"));
            }
            throw new IllegalArgumentException("raw URL 需形如 https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>/SKILL.md: " + url);
        }
        throw new IllegalArgumentException("不支持的技能 URL: " + url);
    }

    /**
     * 安装到 {@code installRoot/<目录名>}：递归拉取 → 暂存（兄弟 temp 目录）→ 校验含 SKILL.md →
     * 原子 rename。返回安装的技能目录名。
     */
    public static String install(Fetcher fetcher, RepoRef ref, Path installRoot) throws IOException {
        Files.createDirectories(installRoot);
        Path staging = Files.createTempDirectory(installRoot, ".staging-");
        try {
            if (ref.singleFile()) {
                installSingleFile(fetcher, ref, staging);
            } else {
                downloadDir(fetcher, ref, staging, "", 0);
            }
            if (!Files.isRegularFile(staging.resolve("SKILL.md"))) {
                throw new IOException("技能目录缺少 SKILL.md,拒绝安装");
            }
            String dirName = ref.path().isEmpty() || ref.singleFile() ? ref.repo()
                    : ref.path().substring(ref.path().lastIndexOf('/') + 1);
            Path target = installRoot.resolve(dirName);
            if (Files.exists(target)) {
                throw new IOException("目标已存在: " + target);
            }
            Files.move(staging, target);
            return dirName;
        } finally {
            deleteRecursiveQuietly(staging);
        }
    }

    // ─── 内部：Contents API 递归拉取 ───

    private static void installSingleFile(Fetcher fetcher, RepoRef ref, Path staging) throws IOException {
        byte[] body = fetcher.fetch(rawUrl(ref.owner(), ref.repo(), ref.ref(), ref.path()));
        checkFileSize(body.length, "SKILL.md");
        Files.write(staging.resolve("SKILL.md"), body);
    }

    /**
     * 递归下载目录：rel 为相对 URL 子路径（"" = ref.path() 根），depth 从 0 计，超限/超量立即中止。
     */
    private static void downloadDir(Fetcher fetcher, RepoRef ref, Path staging, String rel, int depth)
            throws IOException {
        if (depth > MAX_RECURSION_DEPTH) {
            throw new IOException("目录深度超过限额 " + MAX_RECURSION_DEPTH);
        }
        String absPath = rel.isEmpty() ? ref.path() : ref.path() + "/" + rel;
        JsonNode[] entries = listContents(fetcher, ref, absPath);
        for (JsonNode entry : entries) {
            String type = entry.path("type").asText();
            String name = entry.path("name").asText();
            if (name.startsWith(".")) {
                continue; // 跳过点文件/目录
            }
            Path local = staging.resolve(rel).resolve(name);
            if ("file".equals(type)) {
                if (Files.walk(staging).filter(Files::isRegularFile).count() >= MAX_FILE_COUNT) {
                    throw new IOException("文件数超过限额 " + MAX_FILE_COUNT);
                }
                byte[] body = fetcher.fetch(entry.path("download_url").asText());
                checkFileSize(body.length, name);
                checkTotalSize(staging, body.length);
                Files.write(local, body);
            } else if ("dir".equals(type)) {
                Files.createDirectories(local);
                downloadDir(fetcher, ref, staging, rel.isEmpty() ? name : rel + "/" + name, depth + 1);
            }
        }
    }

    /** 累计已写字节 + 本次文件 > 8 MiB 时中止。 */
    private static void checkTotalSize(Path staging, long incomingBytes) throws IOException {
        long total = incomingBytes;
        try (Stream<Path> walk = Files.walk(staging)) {
            total += walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        }
        if (total > MAX_TOTAL_SIZE) {
            throw new IOException("总大小超过限额 " + MAX_TOTAL_SIZE / (1024 * 1024) + " MiB");
        }
    }

    private static JsonNode[] listContents(Fetcher fetcher, RepoRef ref, String absPath) throws IOException {
        String api = "https://api.github.com/repos/" + ref.owner() + "/" + ref.repo()
                + "/contents/" + absPath + (ref.ref() != null ? "?ref=" + ref.ref() : "");
        byte[] body = fetcher.fetch(api);
        JsonNode node = MAPPER.readTree(body);
        if (node.isArray()) {
            return streamNodes(node);
        }
        if (node.isObject() && node.has("type")) {
            return new JsonNode[]{node};
        }
        throw new IOException("GitHub Contents API 返回异常: " + api);
    }

    private static String rawUrl(String owner, String repo, String ref, String path) {
        return "https://raw.githubusercontent.com/" + owner + "/" + repo + "/"
                + (ref == null ? "HEAD" : ref) + "/" + path;
    }

    private static void checkFileSize(long size, String name) throws IOException {
        if (size > MAX_FILE_SIZE) {
            throw new IOException("文件 " + name + " 超过单文件限额 " + MAX_FILE_SIZE / (1024 * 1024) + " MiB");
        }
    }

    private static String joinPath(String[] parts, int from) {
        return String.join("/", java.util.Arrays.copyOfRange(parts, from, parts.length));
    }

    private static JsonNode[] streamNodes(JsonNode array) {
        JsonNode[] out = new JsonNode[array.size()];
        for (int i = 0; i < array.size(); i++) {
            out[i] = array.get(i);
        }
        return out;
    }

    private static void deleteRecursiveQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // 清理失败静默（暂存目录残留不影响正确性）
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
