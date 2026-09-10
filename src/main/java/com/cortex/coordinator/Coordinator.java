package com.cortex.coordinator;

import com.cortex.config.AppConfig;

import java.util.List;
import java.util.Locale;

/**
 * Coordinator Mode（T24/F52-F55/G15）：双锁（config features.coordinatorMode=true 且
 * 环境变量 CORTEX_COORDINATOR_MODE 真值）同时开启才生效；开启后 Lead 工具集收窄、
 * 注入四阶段提示词；运行期不可解锁（N8，取消唯一方式是重启）。
 */
public final class Coordinator {

    /** Coordinator Mode 下 Lead 允许的工具白名单（F53）：剥夺 write_file / edit_file。 */
    public static final List<String> ALLOWED_TOOLS = List.of(
            "Agent", "TeamCreate", "TeamDelete",
            "TaskCreate", "TaskGet", "TaskList", "TaskUpdate",
            "SendMessage",
            "read_file", "glob", "grep", "bash");

    private Coordinator() {}

    /** 双锁判定（F52）。 */
    public static boolean isEnabled(AppConfig cfg) {
        if (cfg == null || !cfg.effectiveCoordinatorMode()) {
            return false;
        }
        return envTruthy(System.getenv("CORTEX_COORDINATOR_MODE"));
    }

    /** 四阶段系统提示词（F55）：核心纪律是「派完队员就停手等汇报」。 */
    public static String systemPromptSuffix() {
        return """

                # Coordinator Mode（协调者模式）

                你现在是团队协调者（Lead）。工作分四个阶段推进：
                1. Research：自己用 read_file/glob/grep 定位目标（唯一允许亲自探索的阶段）
                2. Synthesis：派队员（Agent 工具）执行，等队员汇报后读队员产出的报告/文件做综合
                3. Implementation：派队员实现；全部完成后用 bash 跑 git merge 逐个合回 worktree 分支
                4. Verification：用 bash 跑 git diff / git status / 测试命令核验收敛结果

                纪律（不可协商）：
                - 派出 Agent 或发 SendMessage 之后，禁止立刻调 read_file/glob/grep/bash 自己探索；
                  禁止用 sleep 或反复 TaskList 轮询凑时间。队员完成会以系统通知自动送达，收到后再继续。
                - 派完队员后，唯一该做的事：发一行总结「已派 N 名队员做 X，等结果」，结束本轮。
                - 合并冲突自己用 bash（git diff/edit 语义）推理解决；搞不定就 git merge --abort，
                  保留队员 worktree，向用户报告冲突文件与路径。""";
    }

    /** "1"/"true"/"yes"（大小写不敏感）为真。 */
    static boolean envTruthy(String v) {
        if (v == null) {
            return false;
        }
        return switch (v.strip().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes" -> true;
            default -> false;
        };
    }
}
