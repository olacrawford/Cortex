package com.cortex.team;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 共用文件锁（T5/T9/F33/N3）：CREATE_NEW 抢锁 → 失败 5-100ms 随机抖动重试 10 次 →
 * 持锁超 10 秒视为 stale 直接删除重抢。配合 try-with-resources 使用。
 */
public final class FileLock {

    static final int MAX_RETRIES = 10;
    static final Duration STALE_AFTER = Duration.ofSeconds(10);
    static final long BACKOFF_MIN_MS = 5;
    static final long BACKOFF_MAX_MS = 100;

    private FileLock() {}

    /** 抢锁；返回 AutoCloseable（close = 释放/删锁文件）。失败抛 IOException。 */
    public static AutoCloseable acquire(Path lockPath) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                Files.newOutputStream(lockPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close();
                return () -> Files.deleteIfExists(lockPath);
            } catch (IOException e) {
                last = e; // 已存在 = 他人持锁
            }
            if (isStale(lockPath)) {
                try {
                    Files.deleteIfExists(lockPath);
                    continue; // 清掉 stale 后立即重抢一次
                } catch (IOException ignored) {
                }
            }
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(BACKOFF_MIN_MS, BACKOFF_MAX_MS + 1));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("获取文件锁被中断: " + lockPath, ie);
            }
        }
        throw new IOException("获取文件锁失败（重试 " + MAX_RETRIES + " 次）: " + lockPath, last);
    }

    /** 锁文件 mtime 超过 STALE_AFTER 视为残留（持锁进程崩溃），可安全清除。 */
    private static boolean isStale(Path lockPath) {
        try {
            Instant mtime = Files.getLastModifiedTime(lockPath).toInstant();
            return mtime.plus(STALE_AFTER).isBefore(Instant.now());
        } catch (IOException e) {
            return false; // 锁刚被释放
        }
    }
}
