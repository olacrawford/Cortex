package com.cortex;

import com.cortex.config.AppConfig;
import com.cortex.config.ConfigException;
import com.cortex.config.ConfigLoader;
import com.cortex.tui.CortexModel;
import com.cortex.tui.tea.Program;

/**
 * Cortex 入口：加载配置，启动 TUI。
 */
public class Cortex {

    public static void main(String[] args) {
        String configPath = ".cortex/config.yaml";
        try {
            AppConfig config = ConfigLoader.load(configPath);
            CortexModel model = new CortexModel(config.getProviders());
            Program program = new Program(model);
            model.setProgram(program);
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
