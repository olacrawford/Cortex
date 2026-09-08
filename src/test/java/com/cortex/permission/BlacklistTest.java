package com.cortex.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlacklistTest {

    @Test
    void 高危命令命中() {
        assertTrue(Blacklist.hitsBlacklist("rm -rf /"));
        assertTrue(Blacklist.hitsBlacklist("rm -fr ~"));
        assertTrue(Blacklist.hitsBlacklist("rm -rf $HOME"));
        assertTrue(Blacklist.hitsBlacklist("rm -rf /*"));
        assertTrue(Blacklist.hitsBlacklist("dd if=/dev/zero of=/dev/sda"));
        assertTrue(Blacklist.hitsBlacklist(":(){ :|:& };:"));
        assertTrue(Blacklist.hitsBlacklist("mkfs.ext4 /dev/sda1"));
        assertTrue(Blacklist.hitsBlacklist("echo x > /dev/sda"));
        assertTrue(Blacklist.hitsBlacklist("chmod -R 777 /"));
    }

    @Test
    void 普通命令不命中() {
        assertFalse(Blacklist.hitsBlacklist("rm -rf ./build"));
        assertFalse(Blacklist.hitsBlacklist("rm file.txt"));
        assertFalse(Blacklist.hitsBlacklist("git status"));
        assertFalse(Blacklist.hitsBlacklist("ls -la"));
        assertFalse(Blacklist.hitsBlacklist("echo hello"));
        assertFalse(Blacklist.hitsBlacklist(""));
    }
}
