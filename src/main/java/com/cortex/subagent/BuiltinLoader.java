package com.cortex.subagent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 内置 Agent 定义加载（F32/N4）：classpath 资源 {@code /subagent/builtin/*.md}，
 * Gradle 打 fat jar 时随 src/main/resources 自动打包。
 * 内置资源缺失或解析失败视为代码 bug，立刻抛 RuntimeException（N4 启动期 fail-fast）。
 */
public final class BuiltinLoader {

    /** 文件名清单写死（与 src/main/resources/subagent/builtin/ 一一对应）。 */
    private static final String[] BUILTIN_FILES = {
            "general-purpose.md", "explore.md", "plan.md"
    };

    private BuiltinLoader() {}

    /** 加载全部内置定义，按 name 升序返回。 */
    public static List<Definition> builtinDefinitions() {
        List<Definition> defs = new ArrayList<>();
        for (String file : BUILTIN_FILES) {
            String path = "/subagent/builtin/" + file;
            InputStream in = BuiltinLoader.class.getResourceAsStream(path);
            if (in == null) {
                throw new RuntimeException("builtin agent missing: " + path);
            }
            byte[] data;
            try (in) {
                data = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("builtin agent 读取失败: " + path, e);
            }
            try {
                defs.add(Parser.parseDefinition(data, "classpath:" + path, Source.BUILTIN));
            } catch (Exception e) {
                throw new RuntimeException("builtin agent 解析失败: " + path + ": " + e.getMessage(), e);
            }
        }
        defs.sort(Comparator.comparing(Definition::name));
        return List.copyOf(defs);
    }
}
