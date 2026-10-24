package com.mtxgdn.game.service;

import com.google.gson.Gson;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.RedeemCodeDAO;
import com.mtxgdn.game.entity.RedeemCode;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

public class RedeemCodeService {

    private static final Gson gson = new Gson();
    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();

    /** {@code redeem_codes} / {@code redeemed_codes} 的读写入口（无状态，可共享）。 */
    private static final RedeemCodeDAO REDEEM_DAO = new RedeemCodeDAO();

    // ==================== Create ====================

    public RedeemCode createCode(String code, String name, Map<String, Integer> items,
                                  long gold, long spiritStones, long exp,
                                  int maxUses, String expiresAt, String createdBy) {
        String itemsJson = items != null ? gson.toJson(items) : null;
        long id = REDEEM_DAO.createCode(code, name, itemsJson, gold, spiritStones, exp,
                maxUses, expiresAt, createdBy);
        return id > 0 ? REDEEM_DAO.findById(id) : null;
    }

    // ==================== Query ====================

    public RedeemCode findByCode(String code) {
        return REDEEM_DAO.findByCode(code);
    }

    public RedeemCode findById(long id) {
        return REDEEM_DAO.findById(id);
    }

    public List<RedeemCode> listAll() {
        return REDEEM_DAO.listAll();
    }

    // ==================== Update ====================

    public boolean updateCode(long id, String name, Map<String, Integer> items,
                               long gold, long spiritStones, long exp,
                               int maxUses, String expiresAt) {
        String itemsJson = items != null ? gson.toJson(items) : null;
        return REDEEM_DAO.updateCode(id, name, itemsJson, gold, spiritStones, exp,
                maxUses, expiresAt);
    }

    public boolean deleteCode(long id) {
        return REDEEM_DAO.deleteCode(id);
    }

    // ==================== Redeem ====================

    // ==================== Redeem Action ====================

    /**
     * 执行兑换操作，返回结果描述。
     * 返回 null 表示兑换成功，返回字符串表示失败原因。
     */
    public String doRedeem(String codeStr, long playerId) {
        if (codeStr == null || codeStr.isBlank()) {
            return "请输入兑换码";
        }

        RedeemCode rc = findByCode(codeStr.trim());
        if (rc == null) {
            return "兑换码不存在";
        }

        // 系统控制状态判断
        if (rc.isExpired()) {
            return "此兑换码已过期";
        }
        if (rc.isRedeemed()) {
            return "此兑换码已被兑换完了";
        }
        if (!rc.isActive()) {
            return "此兑换码已失效";
        }

        if (rc.getExpiresAt() != null && !rc.getExpiresAt().isEmpty()) {
            try {
                Timestamp expiry = Timestamp.valueOf(rc.getExpiresAt());
                if (System.currentTimeMillis() > expiry.getTime()) {
                    // 自动标记为已过期
                    setStatus(rc.getId(), RedeemCode.STATUS_EXPIRED);
                    return "此兑换码已过期";
                }
            } catch (Exception ignored) {
            }
        }

        // 提前查找玩家并构建奖励消息（避免在事务内调用 getConnection 导致 SQLite 死锁）
        var p = playerService.getPlayerById(playerId);
        if (p == null) return "玩家不存在";

        StringBuilder rewardMsg = new StringBuilder();
        int rewardCount = 0;

        if (rc.getGold() > 0) {
            rewardMsg.append("金币+" + rc.getGold());
            rewardCount++;
        }
        if (rc.getSpiritStones() > 0) {
            if (rewardCount > 0) rewardMsg.append(", ");
            rewardMsg.append("灵石+" + rc.getSpiritStones());
            rewardCount++;
        }
        if (rc.getExp() > 0) {
            if (rewardCount > 0) rewardMsg.append(", ");
            rewardMsg.append("灵力+" + rc.getExp());
            rewardCount++;
        }

        Map<String, Integer> items = rc.getItems();
        if (items != null) {
            for (Map.Entry<String, Integer> entry : items.entrySet()) {
                String itemKey = entry.getKey();
                int qty = entry.getValue();
                if (!ItemRegistry.contains(itemKey)) {
                    continue;
                }
                if (rewardCount > 0) rewardMsg.append(", ");
                String itemName = com.mtxgdn.util.LangManager.get("item." + itemKey + ".name");
                rewardMsg.append(itemName + "x" + qty);
                rewardCount++;
            }
        }

        if (rewardCount == 0) {
            return "兑换码配置为空";
        }

        // 执行事务
        return DatabaseManager.runTransaction(conn -> {
            // 重复检查
            if (REDEEM_DAO.hasRedeemed(conn, playerId, rc.getId())) {
                return "你已经兑换过此码了";
            }

            // 记录兑换
            String err = REDEEM_DAO.insertRedemption(conn, playerId, rc.getId());
            if (err != null) return err;

            // 增加使用次数
            if (!REDEEM_DAO.incrementUses(conn, rc.getId(), rc.getMaxUses())) {
                return "更新兑换码状态失败";
            }

            // 发放奖励（使用事务连接，避免跨连接死锁）
            if (rc.getGold() > 0) {
                playerService.addGold(conn, playerId, rc.getGold());
            }
            if (rc.getSpiritStones() > 0) {
                itemService.addSpiritStones(conn, playerId, rc.getSpiritStones());
            }
            if (rc.getExp() > 0) {
                playerService.addExperience(conn, playerId, rc.getExp());
            }

            if (items != null) {
                for (Map.Entry<String, Integer> entry : items.entrySet()) {
                    String itemKey = entry.getKey();
                    int qty = entry.getValue();
                    if (!ItemRegistry.contains(itemKey)) {
                        continue;
                    }
                    itemService.addItem(conn, playerId, itemKey, qty);
                }
            }

            return "SUCCESS:" + rewardMsg.toString();
        });
    }

    // ==================== Helpers ====================

    private void setStatus(long codeId, String status) {
        REDEEM_DAO.updateStatus(codeId, status);
    }
}
