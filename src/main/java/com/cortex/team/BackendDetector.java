package com.cortex.team;

import java.io.File;
import java.util.Optional;

/**
 * 后端检测（F14/AC6）：一次性决定，不做运行时回退——
 * $TMUX → TMUX；$TERM_PROGRAM=iTerm.app 且 it2 在 PATH → ITERM2；tmux 在 PATH → TMUX；否则 IN_PROCESS。
 */
public final class BackendDetector {

    /** 环境变量读取口（测试可覆盖）。 */
    public interface Env {
        String get(String key);
    }

    private BackendDetector() {}

    public static BackendType detect() {
        return detect(System::getenv);
    }

    public static BackendType detect(Env env) {
        if (env.get("TMUX") != null) {
            return BackendType.TMUX;
        }
        if ("iTerm.app".equals(env.get("TERM_PROGRAM")) && findOnPath("it2").isPresent()) {
            return BackendType.ITERM2;
        }
        if (findOnPath("tmux").isPresent()) {
            return BackendType.TMUX;
        }
        return BackendType.IN_PROCESS;
    }

    /** 在 PATH 中查找可执行文件。 */
    public static Optional<String> findOnPath(String binary) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            File f = new File(dir, binary);
            if (f.isFile() && f.canExecute()) {
                return Optional.of(f.getAbsolutePath());
            }
        }
        return Optional.empty();
    }
}
