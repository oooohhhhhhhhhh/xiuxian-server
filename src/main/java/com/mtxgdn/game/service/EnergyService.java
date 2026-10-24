package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.EnergyDAO;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class EnergyService {

    /** {@code player_energy} 的读写入口（无状态，可共享）。 */
    private static final EnergyDAO ENERGY_DAO = new EnergyDAO();

    /** 插件注册的自定义物品能量值 key=fullKey → value=能量值 */
    private static final Map<String, Long> customEnergyValues = new LinkedHashMap<>();

    /** 物品→能量 转化损耗率（转化只得原价 80%，兑换按原价，防止低成本物品套利） */
    public static final double CONVERT_LOSS_RATE = 0.8;

    private static volatile Map<String, Long> customEnergyView;

    /**
     * 注册自定义物品能量值（供插件调用）。
     * 若物品未注册，能量值仍会被记录；等物品注册后生效。
     */
    public static void registerItemEnergy(String itemKey, long energyValue) {
        if (energyValue <= 0) {
            throw new IllegalArgumentException("能量值必须大于0: " + itemKey);
        }
        customEnergyValues.put(itemKey, energyValue);
        customEnergyView = null; // 使缓存失效
        System.out.println("[EnergyService] 注册物品能量值: " + itemKey + " = " + energyValue);
    }

    /** 取消注册物品能量转化值。 */
    public static void unregisterItemEnergy(String itemKey) {
        if (itemKey == null) return;
        if (customEnergyValues.remove(itemKey) != null) {
            customEnergyView = null; // 使缓存失效
            System.out.println("[EnergyService] 取消注册物品能量值: " + itemKey);
        }
    }

    /**
     * 解析物品的能量值。
     * 优先返回插件注册的自定义值，其次返回物品本身的 price。
     */
    public static long resolveEnergyValue(String itemKey) {
        Map<String, Long> view = getCustomEnergyView();
        Long custom = view.get(itemKey);
        if (custom != null) {
            return custom;
        }
        Item item = ItemRegistry.get(itemKey);
        if (item != null && item.getPrice() > 0) {
            return item.getPrice();
        }
        return 0;
    }

    /**
     * 物品转化为能量时的实际获得（原价 × 损耗率）。
     * 损耗用于防止"低价获取物品 → 全额转化 → 兑换高价物品"的无损套利。
     */
    public static long resolveConvertGain(String itemKey) {
        long value = resolveEnergyValue(itemKey);
        if (value <= 0) {
            return 0;
        }
        return Math.max(1, (long) (value * CONVERT_LOSS_RATE));
    }

    /**
     * 获取当前所有自定义能量值（只读视图）。
     */
    public static Map<String, Long> getCustomEnergyValues() {
        return getCustomEnergyView();
    }

    private static synchronized Map<String, Long> getCustomEnergyView() {
        if (customEnergyView == null) {
            customEnergyView = Collections.unmodifiableMap(new LinkedHashMap<>(customEnergyValues));
        }
        return customEnergyView;
    }

    /**
     * 获取玩家能量值
     */
    public long getEnergy(long playerId) {
        return ENERGY_DAO.getEnergy(playerId);
    }

    /**
     * 设置玩家能量值（覆盖）
     */
    public void setEnergy(long playerId, long energy) {
        ENERGY_DAO.setEnergy(playerId, energy);
    }

    /**
     * 增加玩家能量值
     */
    public void addEnergy(long playerId, long amount) {
        if (amount <= 0) return;
        ENERGY_DAO.ensureRowExists(playerId);
        ENERGY_DAO.addEnergy(playerId, amount);
    }

    /**
     * 减少玩家能量值，返回是否成功（能量不足时返回 false）。
     * 条件更新保证并发下不会扣成负数。
     */
    public boolean removeEnergy(long playerId, long amount) {
        if (amount <= 0) return true;
        return ENERGY_DAO.removeEnergy(playerId, amount);
    }
}
