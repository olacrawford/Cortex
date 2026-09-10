package com.cortex.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 路径沙箱（F2/N2）：把文件类工具的读写限定在项目根目录内。
 * 判定顺序固定：<b>先解析符号链接（或最近已存在祖先目录），再做前缀比对</b>——
 * 软链接指向项目外的目标判逃逸；新建文件（含未创建中间目录）按最近已存在祖先解析，不误判。
 * 只对文件类工具生效；Bash 不走沙箱（其危险由黑名单 + 规则 + 模式兜底覆盖）。
 */
public final class Sandbox {

    private Sandbox() {}

    /** 解析项目根为绝对且真实的路径。 */
    public static Path resolveRoot(Path root) throws IOException {
        return root.toAbsolutePath().toRealPath();
    }

    /**
     * 解析绝对路径的符号链接：目标存在则 {@code toRealPath()}；
     * 不存在则取最近<b>已存在祖先</b>目录 toRealPath 后把剩余段拼回。
     */
    public static Path evalSymlinksOrAncestor(Path abs) throws IOException {
        if (Files.exists(abs)) {
            return abs.toRealPath();
        }
        Path remaining = Path.of("");
        Path cursor = abs;
        while (!Files.exists(cursor)) {
            Path parent = cursor.getParent();
            if (parent == null) {
                // 到达文件系统根仍不存在（极端情形），直接按字面返回
                return abs.toAbsolutePath().normalize();
            }
            remaining = cursor.getFileName() == null
                    ? remaining
                    : cursor.getFileName().resolve(remaining);
            cursor = parent;
        }
        return cursor.toRealPath().resolve(remaining);
    }

    /** 目标路径（相对或绝对，可不存在）是否落在项目根内。空 path 视为 root 本身。 */
    public static boolean sandboxOK(Path root, String path) {
        Path p;
        try {
            p = (path == null || path.isEmpty())
                    ? root
                    : Path.of(path);
        } catch (Exception e) {
            return false;
        }
        Path abs = p.isAbsolute() ? p : root.resolve(p);
        Path resolved;
        try {
            resolved = evalSymlinksOrAncestor(abs);
        } catch (IOException e) {
            return false;
        }
        if (resolved.equals(root) || resolved.startsWith(root)) {
            return true;
        }
        // 阶段14 N9 白名单：系统临时目录 /tmp 与 macOS 真实路径 /private/tmp（逃逸检测已先行）
        return resolved.startsWith(java.nio.file.Path.of(java.io.File.separator, "tmp"))
                || resolved.startsWith(java.nio.file.Path.of(java.io.File.separator, "private", "tmp"));
    }
}
