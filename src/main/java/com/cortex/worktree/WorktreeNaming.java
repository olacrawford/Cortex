package com.cortex.worktree;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Worktree 命名（F21/G10）：SubAgent 临时 Worktree 名 {@code agent-a<7位hex>}，
 * 与正则 {@link #EPHEMERAL_PATTERN} 对应——sweepStale 只清理该模式。
 */
public final class WorktreeNaming {

    /** 临时（SubAgent 自动创建）Worktree 的名字模式。 */
    public static final Pattern EPHEMERAL_PATTERN = Pattern.compile("^agent-a[0-9a-f]{7}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private WorktreeNaming() {}

    /** 生成 {@code agent-a} + 7 位随机小写 hex。 */
    public static String randomAgentName() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("agent-a");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.substring(0, "agent-a".length() + 7);
    }
}
