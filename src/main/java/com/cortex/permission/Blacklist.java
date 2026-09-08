package com.cortex.permission;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 危险命令黑名单（F1/N1）：对命令执行类工具的命令串做启发式匹配，命中即 DENY。
 *
 * <p>这是<b>最高优先级</b>的防御层：不可被任何规则、模式（含 bypassPermissions）或配置放开，
 * 用户不可增删或关闭。它是启发式防御而非完备保证——不追求穷尽所有危险命令，
 * 防御纵深由沙箱、规则引擎、模式兜底与人在回路补足。
 */
public final class Blacklist {

    private Blacklist() {}

    private static final List<Pattern> PATTERNS = List.of(
            // 递归强删根 / 家目录 / 根通配：rm -rf / 、rm -fr ~ 、rm -rf /* 等
            Pattern.compile("rm\\s+(-[a-zA-Z]*[rf][a-zA-Z]*\\s+)+(\"?(/|~|\\$HOME|/\\*)\"?)(\\s|$)"),
            // 写块设备：dd if=... of=/dev/sda 等
            Pattern.compile("dd\\s+[^;|&]*of=/dev/"),
            // fork 炸弹：:(){ :|:& };:
            Pattern.compile(":\\(\\)\\s*\\{.*\\|.*&\\s*\\}\\s*;?"),
            // 格式化文件系统
            Pattern.compile("mkfs\\.[a-z0-9]+"),
            // 重定向覆盖磁盘设备
            Pattern.compile(">\\s*/dev/(sd[a-z]|hd[a-z]|nvme\\d+|disk)"),
            // 对根目录做递归 777 授权
            Pattern.compile("chmod\\s+-R\\s+0?777\\s+/\\s*$"),
            // 清空根下所有文件（> 通配由 shell 展开的情形）
            Pattern.compile("sh\\s+-c\\s+[\"']?\\s*(rm\\s+-[a-zA-Z]*r[a-zA-Z]*f?\\s+/)"));

    /** 供引擎构造持有的已编译模式集。 */
    static List<Pattern> patterns() {
        return PATTERNS;
    }

    /** 任一黑名单模式命中即真。 */
    public static boolean hitsBlacklist(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        for (Pattern p : PATTERNS) {
            if (p.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }
}
