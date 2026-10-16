package com.mtxgdn.plugin.experimental;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 实验性插件：能量转化 / 等价交换。
 * <p>
 * 配置键: {@code plugins.experimental_energy_exchange}（默认 true，见 config/plugins.yml）
 * 功能: 物品转化为能量、能量兑换物品（{@code /转化}、{@code /交换} 指令与
 * {@code energy/convert}、{@code energy/exchange} 接口）。
 * <p>
 * 与稳定模块 {@code energy}（能量系统）相互独立：本插件管的是“等价交换”这层实验性玩法，
 * 由旧 {@code config/experimental.yml} 的 {@code experimental.energy_exchange} 迁移而来。
 */
public class EnergyExchangePlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "experimental_energy_exchange";
    }

    @Override
    public String getVersion() {
        return "0.1.0";
    }

    @Override
    public String getDescription() {
        return "实验性：能量转化/等价交换（物品⇄能量）";
    }

    @Override
    public String getCategory() {
        return "experimental";
    }

    /** 等价交换此前已默认开放（旧 experimental.yml 中 energy_exchange 默认 true），保持默认开启。 */
    @Override
    public boolean getDefaultEnabled() {
        return true;
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("实验性功能已启用: 能量转化（等价交换）");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("实验性功能已停用: 能量转化（等价交换）");
    }
}
