package com.cortex.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内置命令注册中心：名字/别名统一索引、启动期冲突检测（F2）、
 * 按名字字典序的可见命令清单（/help 与补全菜单的单一信源，N7）、命令名前缀匹配（F24/F25）。
 */
public final class CommandRegistry {

    /** 主名 + 别名都映射到同一 Command；key 已小写化。 */
    private final Map<String, Command> byName = new HashMap<>();
    /** 按 name 字典序排序的可见（非 hidden）命令，供 /help 与补全菜单使用。 */
    private final List<Command> visible = new ArrayList<>();

    /**
     * 注册一条命令。名字/别名任一与已注册键重复立即抛 {@link IllegalStateException}（含冲突键名，N4），
     * 让进程在启动期终止而不是运行时静默失效。
     */
    public void register(Command c) {
        List<String> keys = new ArrayList<>();
        keys.add(c.name());
        keys.addAll(c.aliases());
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("命令名/别名不能为空");
            }
            String normalized = key.toLowerCase();
            if (byName.containsKey(normalized)) {
                throw new IllegalStateException("命令名/别名冲突: " + key);
            }
        }
        for (String key : keys) {
            byName.put(key.toLowerCase(), c);
        }
        if (!c.hidden()) {
            visible.add(c);
            visible.sort(java.util.Comparator.comparing(Command::name));
        }
    }

    /** 按名字或别名查找（大小写不敏感，F4）。 */
    public Optional<Command> lookup(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(name.toLowerCase()));
    }

    /** 已排序的可见命令副本（/help 与补全菜单数据源）。 */
    public List<Command> visible() {
        return List.copyOf(visible);
    }

    /**
     * 命令名前缀匹配（补全菜单用）：去掉前导 "/" 并小写化，仅匹配主名，
     * 不匹配别名与描述（F25）；prefix 为空时返回全部可见命令。
     */
    public List<Command> prefixMatch(String prefix) {
        String p = prefix == null ? "" : prefix.strip().toLowerCase();
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        List<Command> hits = new ArrayList<>();
        for (Command c : visible) {
            if (c.name().startsWith(p)) {
                hits.add(c);
            }
        }
        return List.copyOf(hits);
    }
}
