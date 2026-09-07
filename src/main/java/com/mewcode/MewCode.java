package com.mewcode;

import com.mewcode.config.AppConfig;
import com.mewcode.config.ConfigException;
import com.mewcode.config.ConfigLoader;
import com.mewcode.tui.MewCodeModel;
import com.mewcode.tui.tea.Program;

/**
 * MewCode 入口：加载配置，启动 TUI。
 */
public class MewCode {

    public static void main(String[] args) {
        String configPath = ".mewcode/config.yaml";
        try {
            AppConfig config = ConfigLoader.load(configPath);
            MewCodeModel model = new MewCodeModel(config.getProviders());
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
