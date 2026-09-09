package com.cortex.tool;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 工具执行上下文（阶段13 F16）：不可变对象，随 {@link Tool#execute(ToolContext, String)}
 * 传入工具。当前承载 explicit cwd——Worktree 场景下工具调用以 Worktree 路径为基准解析
 * 相对路径（N4：不用进程级 chdir）。ctx 不暴露给模型、不进工具 schema（F19）。
 */
public final class ToolContext {

    /** 空 ctx：无 explicit cwd，所有解析回落 JVM 进程当前目录（与阶段12 之前行为一致）。 */
    public static final ToolContext EMPTY = new ToolContext(null);

    private final Path cwd; // 可空；非空时已 toAbsolutePath+normalize

    public ToolContext(Path cwd) {
        this.cwd = cwd == null ? null : cwd.toAbsolutePath().normalize();
    }

    /** 返回带 explicit cwd 的新 ctx。 */
    public ToolContext withCwd(Path dir) {
        return new ToolContext(dir);
    }

    /** explicit cwd（可空——空表示回落 JVM 当前目录）。 */
    public Optional<Path> cwd() {
        return Optional.ofNullable(cwd);
    }

    /**
     * 路径解析（F16）：绝对路径原样返回（normalize）；相对路径以 ctx cwd（优先）或
     * JVM 当前目录为基准拼接；null/空串返回基准目录本身。
     */
    public Path resolvePath(String p) {
        Path base = cwd != null ? cwd : Path.of("").toAbsolutePath();
        if (p == null || p.isEmpty()) {
            return base;
        }
        Path path = Path.of(p);
        return path.isAbsolute() ? path.normalize() : base.resolve(path).normalize();
    }
}
