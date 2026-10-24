package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.EquipmentDataCheckerDAO;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.util.GameLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 启动时的装备数据一致性自检。
 *
 * <p><b>这不是"修理装备"</b>——玩家侧的耐久与修理在 {@link DurabilityService}，
 * 由 {@code /修理} 指令触发。本类做的是启动期的一次性数据体检，处理的是
 * 历史脏数据（重复装备、指向已删除物品的槽位），和游戏内的修理玩法无关。
 * 类名曾经叫 {@code EquipmentFixService}，容易被误读成修理系统，故改名。</p>
 *
 * <p>它只做两件事：</p>
 * <ol>
 *   <li>装备槽位指向的物品 key 在物品注册表里不存在 → 删除该槽位（否则读取属性时会 NPE）；</li>
 *   <li>已装备的物品在背包里还留有一份 → 背包侧扣 1（历史上装卸未走事务留下的重复）。</li>
 * </ol>
 */
public class EquipmentDataChecker {

    private static final GameLogger LOG = GameLogger.getLogger(EquipmentDataChecker.class);
    private static boolean hasRun = false;

    /** {@code players} / {@code players_equipment} / {@code players_items} 的读写入口。 */
    private static final EquipmentDataCheckerDAO CHECKER_DAO = new EquipmentDataCheckerDAO();

    /**
     * 执行自检。进程内只跑一次 —— 启动流程只调用一次，重复调用没有意义，
     * 而且全表扫描在多玩家服上是实打实的开销。
     */
    public void checkEquipmentData() {
        checkEquipmentData(false);
    }

    /**
     * 强制执行自检（忽略"只跑一次"的限制），供管理指令 {@code /修复装备} 使用。
     *
     * <p>启动时的自检已经跑过，管理员再点一次按钮时如果还被 {@code hasRun} 拦住，
     * 这条指令就永远是空操作了 —— 所以管理入口必须能绕过它。</p>
     */
    public void forceCheckEquipmentData() {
        checkEquipmentData(true);
    }

    private void checkEquipmentData(boolean force) {
        if (hasRun && !force) {
            LOG.info("装备数据自检已执行过，跳过");
            return;
        }
        hasRun = true;
        LOG.info("开始检查装备数据一致性...");
        List<Long> playerIds = CHECKER_DAO.findAllPlayerIds();

        int fixedCount = 0;
        int errorCount = 0;

        for (Long playerId : playerIds) {
            try {
                if (checkPlayerEquipment(playerId)) {
                    fixedCount++;
                }
            } catch (Exception e) {
                LOG.error("检查玩家 " + playerId + " 的装备数据失败: " + e.getMessage());
                errorCount++;
            }
        }

        LOG.info("装备数据检查完成: 修复 " + fixedCount + " 个玩家, 错误 " + errorCount + " 个");
    }

    private boolean checkPlayerEquipment(long playerId) {
        final boolean[] fixed = {false};

        DatabaseManager.runTransaction(conn -> {
            try {
                Map<String, String> equipment = CHECKER_DAO.getEquipment(conn, playerId);

                for (String slot : equipment.keySet()) {
                    String itemKey = equipment.get(slot);
                    Item item = ItemRegistry.get(itemKey);

                    if (item == null) {
                        LOG.warn("玩家 " + playerId + " 的 " + slot + " 槽位装备不存在: " + itemKey);
                        CHECKER_DAO.removeFromEquipment(conn, playerId, slot);
                        fixed[0] = true;
                        continue;
                    }

                    long inBackpack = CHECKER_DAO.getItemCount(conn, playerId, itemKey);
                    if (inBackpack > 0) {
                        LOG.info("玩家 " + playerId + " 的 " + slot + " 槽位装备在背包中存在，正在清理重复项...");
                        CHECKER_DAO.removeItem(conn, playerId, itemKey, 1);
                        fixed[0] = true;
                    }
                }

                List<String> equipmentKeys = CHECKER_DAO.getEquipmentKeys(conn, playerId);
                List<String> equipmentItemsInBackpack = CHECKER_DAO.getBackpackItemKeys(conn, playerId);

                for (String itemKey : equipmentItemsInBackpack) {
                    if (!equipmentKeys.contains(itemKey)) {
                        Item item = ItemRegistry.get(itemKey);
                        if (item != null && item.getType() == ItemType.EQUIPMENT) {
                            LOG.warn("玩家 " + playerId + " 背包中有装备物品但未装备: " + item.getName());
                        }
                    }
                }

            } catch (java.sql.SQLException e) {
                throw new RuntimeException("检查装备数据失败: " + e.getMessage(), e);
            }
            return true;
        });

        return fixed[0];
    }
}
