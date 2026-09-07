package com.mewcode.config;

import java.util.List;

public class AppConfig {
    private List<ProviderConfig> providers;

    public List<ProviderConfig> getProviders() { return providers; }
    public void setProviders(List<ProviderConfig> providers) { this.providers = providers; }
}