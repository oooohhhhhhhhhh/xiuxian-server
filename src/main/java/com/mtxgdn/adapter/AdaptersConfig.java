package com.mtxgdn.adapter;

import java.util.ArrayList;
import java.util.List;

/**
 * adapters.json 的顶层数据结构。
 */
public class AdaptersConfig {
    private List<AdapterConfig> adapters = new ArrayList<>();

    public AdaptersConfig() {
    }

    public AdaptersConfig(List<AdapterConfig> adapters) {
        this.adapters = adapters != null ? adapters : new ArrayList<>();
    }

    public List<AdapterConfig> getAdapters() {
        return adapters;
    }

    public void setAdapters(List<AdapterConfig> adapters) {
        this.adapters = adapters != null ? adapters : new ArrayList<>();
    }

    /** 创建深拷贝（用于热重载对比） */
    public AdaptersConfig copy() {
        List<AdapterConfig> copyList = new ArrayList<>();
        for (AdapterConfig ac : adapters) {
            copyList.add(ac.copy());
        }
        return new AdaptersConfig(copyList);
    }
}
