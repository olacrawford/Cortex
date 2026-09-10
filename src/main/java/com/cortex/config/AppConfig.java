package com.cortex.config;

import java.util.List;

public class AppConfig {
    private List<ProviderConfig> providers;
    /** 阶段12（N6）：SubAgent 后台能力开关；null = 默认 true。 */
    private Boolean enableSubAgentBackground;
    /** 阶段14（T25）：功能开关段（features:），缺省全 false。 */
    private Features features = new Features(false, false);

    /** features 段（coordinator_mode / fork_teammate，T25）。 */
    public record Features(boolean coordinatorMode, boolean forkTeammate) {}

    public Features getFeatures() { return features; }
    public void setFeatures(Features features) { this.features = features; }

    /** Coordinator Mode 配置锁（环境变量为第二把锁，见 coordinator.Coordinator）。 */
    public boolean effectiveCoordinatorMode() {
        return features != null && features.coordinatorMode();
    }

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