package com.cortex.config;

import java.util.List;

public class AppConfig {
    private List<ProviderConfig> providers;
    /** 阶段12（N6）：SubAgent 后台能力开关；null = 默认 true。 */
    private Boolean enableSubAgentBackground;

    public List<ProviderConfig> getProviders() { return providers; }
    public void setProviders(List<ProviderConfig> providers) { this.providers = providers; }

    public Boolean getEnableSubAgentBackground() { return enableSubAgentBackground; }
    public void setEnableSubAgentBackground(Boolean enableSubAgentBackground) {
        this.enableSubAgentBackground = enableSubAgentBackground;
    }

    /** 生效值：false 时 run_in_background / 超时切后台 / Fork 全部退化为前台同步或报错。 */
    public boolean effectiveEnableSubAgentBackground() {
        return enableSubAgentBackground == null || enableSubAgentBackground;
    }
}