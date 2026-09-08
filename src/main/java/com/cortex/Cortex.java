package com.cortex;

import com.cortex.config.AppConfig;
import com.cortex.config.ConfigException;
import com.cortex.config.ConfigLoader;
import com.cortex.permission.PermissionEngine;
import com.cortex.tool.ToolRegistry;
import com.cortex.tui.CortexModel;
import com.cortex.tui.tea.Program;

import java.nio.file.Path;

/**
 * Cortex 入口：加载配置，构造工具注册中心与权限引擎，启动 TUI。
 */
public class Cortex {

    public static void main(String[] args) {
        String configPath = ".cortex/config.yaml";
        try {
            AppConfig config = ConfigLoader.load(configPath);
            ToolRegistry registry = ToolRegistry.createDefault();
            PermissionEngine engine = PermissionEngine.create(Path.of("").toAbsolutePath());
            CortexModel model = new CortexModel(config.getProviders(), registry, engine);
            Program program = new Program(model);
            program.run();
        } catch (ConfigException e) {
            System.err.println("配置错误: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            System.exit(1);
        }
    }
}
