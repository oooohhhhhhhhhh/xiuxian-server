package com.mtxgdn.game.service;

import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.db.CaveDAO;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.Cave;
import com.mtxgdn.util.GameLogger;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.mtxgdn.i18n.I18n;

public class CaveService {

    private static final GameLogger LOG = GameLogger.getLogger(CaveService.class);

    private static final CaveDAO CAVE_DAO = new CaveDAO();

    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();

    private static final ScheduledExecutorService scheduler = com.mtxgdn.util.Schedulers.newDaemonSingle("cave-spirit-energy");

    static {
        scheduler.scheduleAtFixedRate(CaveService::tickSpiritEnergy, 10, 10, TimeUnit.SECONDS);
        LOG.info("洞府灵气自动增长任务已启动");
    }

    public static void shutdown() {
        scheduler.shutdown();
        LOG.info("洞府灵气自动增长任务已停止");
    }

    public Cave getCaveByPlayerId(long playerId) {
        return CAVE_DAO.findCaveByPlayerId(playerId);
    }

    public Map<String, Object> createCave(long playerId, String caveName) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", I18n.t("cave.character_does_not_exist"));
            return result;
        }

        if (player.getRealm() < Cave.MIN_REALM_CREATE) {
            String realmName = GameConfigLoader.getRealmConfig(player.getRealm(), 0) != null
                    ? GameConfigLoader.getRealmConfig(player.getRealm(), 0).getFullName() : "凡人";
            result.put("success", false);
            result.put("message", I18n.t("cave.realm_too_low", realmName));
            return result;
        }

        if (getCaveByPlayerId(playerId) != null) {
            result.put("success", false);
            result.put("message", I18n.t("cave.you_already_have_a_cave"));
            return result;
        }

        long spiritStones = itemService.getSpiritStoneCount(playerId);
        if (spiritStones < Cave.CREATE_COST_SPIRIT_STONES) {
            result.put("success", false);
            result.put("message", I18n.t("cave.not_enough_spirit_stones_2", Cave.CREATE_COST_SPIRIT_STONES, spiritStones));
            return result;
        }

        String caveNameFinal = caveName != null && !caveName.trim().isEmpty() ? caveName.trim() : "山间洞府";
        if (caveNameFinal.length() > 10) {
            caveNameFinal = caveNameFinal.substring(0, 10);
        }
        final String name = caveNameFinal;

        // 修复"开辟洞府失败实际成功"：不能在事务内用另一条连接查询刚插入的行
        //（事务未提交时新连接看不到该行，导致返回 null 报失败，但插入实际已提交）。
        // 改为：事务内只返回生成的 id，提交后再查询。
        Long caveId = DatabaseManager.runTransaction(conn -> {
            if (!itemService.removeSpiritStones(conn, playerId, Cave.CREATE_COST_SPIRIT_STONES)) {
                throw new SQLException("灵石扣除失败");
            }
            return CAVE_DAO.insertCave(conn, playerId, name,
                    Cave.getMaxSpiritEnergyForLevel(1),
                    Cave.getCultivationBonusForLevel(1),
                    Cave.getStorageBonusForLevel(1),
                    System.currentTimeMillis());
        });

        Cave cave = caveId != null && caveId > 0 ? getCaveById(caveId) : null;

        if (cave != null) {
            result.put("success", true);
            result.put("message", I18n.t("cave.congratulations_to_on_establishing", player.getName(), name, Cave.CREATE_COST_SPIRIT_STONES));
            result.put("cave", cave);
        } else {
            result.put("success", false);
            result.put("message", I18n.t("cave.failed_to_establish_the_cave"));
        }
        return result;
    }

    private Cave getCaveById(long caveId) {
        return CAVE_DAO.findCaveById(caveId);
    }

    public Map<String, Object> collectSpiritEnergy(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Cave cave = getCaveByPlayerId(playerId);
        if (cave == null) {
            result.put("success", false);
            result.put("message", I18n.t("cave.you_don_t_3"));
            return result;
        }

        long collectInterval = 5 * 60 * 1000L;
        Long collectedEnergy = DatabaseManager.runTransaction(conn -> {
            CaveDAO.SpiritEnergyRow locked = CAVE_DAO.lockSpiritEnergy(conn, cave.getId());
            long spiritEnergy = locked.spiritEnergy;
            long lastCollectTime = locked.lastCollectTime;

            long now = System.currentTimeMillis();
            if (lastCollectTime > 0 && (now - lastCollectTime) < collectInterval) {
                return null;
            }

            if (spiritEnergy <= 0) {
                return null;
            }

            CAVE_DAO.resetSpiritEnergy(conn, cave.getId(), now);

            // 在事务内发放灵石，确保灵能重置和灵石发放原子性
            int formationBoost = com.mtxgdn.common.service.ServiceRegistry.getFormationService().getTotalSpiritEnergyBoost(playerId);
            long boostedEnergy = spiritEnergy;
            if (formationBoost > 0) {
                boostedEnergy = spiritEnergy * (100 + formationBoost) / 100;
            }
            itemService.addSpiritStones(conn, playerId, boostedEnergy);
            return boostedEnergy;
        });

        if (collectedEnergy == null) {
            long now = System.currentTimeMillis();
            long elapsed = now - cave.getLastCollectTime();
            if (elapsed < collectInterval) {
                long remaining = (collectInterval - elapsed) / 1000;
                result.put("success", false);
                result.put("message", I18n.t("cave.the_spiritual_energy", remaining));
            } else {
                result.put("success", false);
                result.put("message", I18n.t("cave.not_enough_cave_dwelling_spiritual"));
            }
            return result;
        }

        result.put("success", true);
        result.put("message", I18n.t("cave.collected_the_cave_dwelling_s", collectedEnergy));
        result.put("collected", collectedEnergy);
        return result;
    }

    public Map<String, Object> meditate(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Cave cave = getCaveByPlayerId(playerId);
        if (cave == null) {
            result.put("success", false);
            result.put("message", I18n.t("cave.you_don_t"));
            return result;
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", I18n.t("cave.character_does_not_exist"));
            return result;
        }

        int caveBonus = cave.getCultivationBonus();
        int formationBonus = com.mtxgdn.common.service.ServiceRegistry.getFormationService().getTotalCultivationBonus(playerId);
        int totalBonus = caveBonus + formationBonus;
        long expGain = 100 + (long) totalBonus * 10;

        int formationResist = com.mtxgdn.common.service.ServiceRegistry.getFormationService().getTotalHeartDemonResist(playerId);
        int totalCaveLevelForHeartDemon = cave.getLevel() + (formationResist / 2);

        HeartDemonService heartDemonService = ServiceRegistry.getHeartDemonService();
        HeartDemonService.HeartDemonResult heartDemon = heartDemonService.processCultivationWithCave(playerId, expGain, 30, totalCaveLevelForHeartDemon);

        if (heartDemon.triggered) {
            expGain = heartDemon.netExpChange;
            if (expGain < 0) expGain = 0;
            playerService.addExperience(playerId, expGain);
            StringBuilder msg = new StringBuilder();
            msg.append("在洞府中打坐冥想\n");
            msg.append("⚠ 心魔劫: ").append(heartDemon.narrative).append("\n");
            msg.append("实际获得: ").append(expGain).append(" 经验值\n");
            msg.append("洞府修炼加成: +").append(caveBonus).append("%");
            if (formationBonus > 0) {
                msg.append("（阵法加成: +").append(formationBonus).append("%）");
            }
            result.put("success", true);
            result.put("message", msg.toString());
            result.put("expGain", expGain);
            result.put("bonus", totalBonus);
            result.put("heartDemon", heartDemon);
        } else {
            playerService.addExperience(playerId, expGain);
            StringBuilder msg = new StringBuilder();
            msg.append("在洞府中打坐冥想，获得 ").append(expGain).append(" 经验值\n");
            msg.append("洞府修炼加成: +").append(caveBonus).append("%");
            if (formationBonus > 0) {
                msg.append("（阵法加成: +").append(formationBonus).append("%）");
            }
            result.put("success", true);
            result.put("message", msg.toString());
            result.put("expGain", expGain);
            result.put("bonus", totalBonus);
        }
        return result;
    }

    public Map<String, Object> levelUp(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Cave cave = getCaveByPlayerId(playerId);
        if (cave == null) {
            result.put("success", false);
            result.put("message", I18n.t("cave.you_don_t_3"));
            return result;
        }

        if (cave.getLevel() >= Cave.MAX_LEVEL) {
            result.put("success", false);
            result.put("message", I18n.t("cave.the_cave_dwelling_has_reached", Cave.MAX_LEVEL));
            return result;
        }

        long cost = Cave.getLevelUpCost(cave.getLevel());
        long spiritStones = itemService.getSpiritStoneCount(playerId);
        if (spiritStones < cost) {
            result.put("success", false);
            result.put("message", I18n.t("cave.not_enough_spirit_stones", cost, spiritStones));
            return result;
        }

        DatabaseManager.runTransaction(conn -> {
            if (!itemService.removeSpiritStones(conn, playerId, cost)) {
                throw new SQLException("灵石扣除失败");
            }
            int newLevel = cave.getLevel() + 1;
            CAVE_DAO.updateCaveLevel(conn, cave.getId(), newLevel,
                    Cave.getMaxSpiritEnergyForLevel(newLevel),
                    Cave.getCultivationBonusForLevel(newLevel),
                    Cave.getStorageBonusForLevel(newLevel));
            return null;
        });

        int newLevel = cave.getLevel() + 1;
        result.put("success", true);
        result.put("message", I18n.t("cave.cave_dwelling_advanced_to_level", cave.getName(), newLevel, cost, Cave.getCultivationBonusForLevel(newLevel), Cave.getStorageBonusForLevel(newLevel)));
        result.put("newLevel", newLevel);
        return result;
    }

    public static void tickSpiritEnergy() {

        // 服务器正在关闭、或测试里刚换了连接池：这时候没有库可查，
        // 继续跑只会刷一屏 "Connection is closed" 把真问题淹掉。
        if (!com.mtxgdn.db.DatabaseManager.isReady()) {
            return;
        }
        // 内置模块门控：洞府模块停用后暂停灵气自动收集
        if (!com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown("cave")) return;
        CAVE_DAO.incrementSpiritEnergy();
    }

    public int getStorageBonus(long playerId) {
        Cave cave = getCaveByPlayerId(playerId);
        return cave != null ? cave.getStorageBonus() : 0;
    }
}
