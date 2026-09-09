package com.cortex.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandRegistryTest {

    private static Command cmd(String name, String... aliases) {
        return new Command(name, List.of(aliases), name + " 的描述", Kind.LOCAL, false, (ui, args) -> {});
    }

    @Test
    void registerOk_lookup命中且大小写不敏感() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(cmd("help"));
        assertTrue(reg.lookup("help").isPresent());
        assertTrue(reg.lookup("HELP").isPresent());
        assertEquals("help", reg.lookup("HELP").orElseThrow().name());
        assertTrue(reg.lookup("nope").isEmpty());
    }

    @Test
    void registerDuplicateNameThrows_异常含冲突名() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(cmd("help"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> reg.register(cmd("help")));
        assertTrue(ex.getMessage().contains("help"));
    }

    @Test
    void registerDuplicateAliasThrows_异常含冲突别名() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(cmd("clear", "cls"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> reg.register(cmd("other", "cls")));
        assertTrue(ex.getMessage().contains("cls"));
    }

    @Test
    void visibleSorted_按名字字典序() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(cmd("status"));
        reg.register(cmd("clear"));
        reg.register(cmd("help"));
        reg.register(cmd("do"));
        List<Command> visible = reg.visible();
        assertEquals(List.of("clear", "do", "help", "status"),
                visible.stream().map(Command::name).toList());
    }

    @Test
    void prefixMatch_仅前缀匹配主名() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(cmd("session"));
        reg.register(cmd("status"));
        reg.register(cmd("help"));
        reg.register(new Command("memory", List.of(), "memory 的描述", Kind.LOCAL, false, (ui, args) -> {}));

        assertEquals(List.of("session", "status"),
                reg.prefixMatch("/s").stream().map(Command::name).toList());
        assertEquals(List.of("session"), reg.prefixMatch("ses").stream().map(Command::name).toList());
        // 空前缀返回全部可见命令
        assertEquals(4, reg.prefixMatch("").size());
        // 描述里的词不参与匹配（不命中 memory 的"描述"）
        assertTrue(reg.prefixMatch("desc").isEmpty());
    }

    @Test
    void hidden命令不进visible但仍可命中() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(new Command("secret", List.of(), "隐藏命令", Kind.LOCAL, true, (ui, args) -> {}));
        assertTrue(reg.visible().isEmpty());
        assertTrue(reg.prefixMatch("").isEmpty());
        assertTrue(reg.prefixMatch("sec").isEmpty());
        assertTrue(reg.lookup("secret").isPresent());
    }
}
