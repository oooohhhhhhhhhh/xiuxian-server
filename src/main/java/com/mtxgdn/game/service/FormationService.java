package com.mtxgdn.game.service;

import com.mtxgdn.common.result.OperationResult;
import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.FormationDAO;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Formation;
import com.mtxgdn.game.formation.FormationUpgradeResult;
import com.mtxgdn.util.GameLogger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.mtxgdn.i18n.I18n;

import java.sql.SQLException;

public class FormationService {

    private static final GameLogger LOG = GameLogger.getLogger(FormationService.class);

    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();

    /** {@code formations} 的读写入口（无状态，可共享）。 */
    private static final FormationDAO FORMATION_DAO = new FormationDAO();

    private static final ScheduledExecutorService scheduler = com.mtxgdn.util.Schedulers.newDaemonSingle("formation-tick");

    static {
        scheduler.scheduleAtFixedRate(FormationService::tickFormations, 60, 60, TimeUnit.SECONDS);
        LOG.info("阵法定时检查任务已启动");
    }

    public static void shutdown() {
        scheduler.shutdown();
        LOG.info("阵法定时检查任务已停止");
    }

    public Formation getActiveFormation(long playerId) {
        Formation f = FORMATION_DAO.findActiveFormation(playerId);
        if (f != null && f.isExpired()) {
            FORMATION_DAO.deactivate(f.getId());
            return null;
        }
        return f;
    }

    public OperationResult placeFormation(long playerId, String formationKey, int level) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            return OperationResult.fail(I18n.t("formation.character_not_found"));
        }

        if (com.mtxgdn.common.service.ServiceRegistry.getCaveService().getCaveByPlayerId(playerId) == null) {
            return OperationResult.fail(I18n.t("formation.you_do"));
        }

        if (getActiveFormation(playerId) != null) {
            return OperationResult.fail(I18n.t("formation.you_have_already_set_up"));
        }

        long cost = Formation.getPlaceCost(formationKey, level);
        long spiritStones = itemService.getSpiritStoneCount(playerId);
        if (spiritStones < cost) {
            return OperationResult.fail(I18n.t("formation.not_enough_spirit_stones_2", cost, spiritStones));
        }

        DatabaseManager.runTransaction(conn -> {
            if (!itemService.removeSpiritStones(conn, playerId, cost)) {
                throw new SQLException("灵石扣除失败");
            }
            long now = System.currentTimeMillis();
            long durationMs = (long) Formation.getBaseDuration(formationKey) * 60 * 1000;
            FORMATION_DAO.insertFormation(conn, playerId, formationKey,
                    Formation.getNameForKey(formationKey), level,
                    Formation.getSpiritEnergyBoostForLevel(formationKey, level),
                    Formation.getCultivationBonusForLevel(formationKey, level),
                    Formation.getDefenseBonusForLevel(formationKey, level),
                    Formation.getHeartDemonResistForLevel(formationKey, level),
                    Formation.getBaseDuration(formationKey), now, now + durationMs);
            return null;
        });

        Formation formation = getActiveFormation(playerId);
        return OperationResult.ok(I18n.t("formation.successfully_set_up", formation.getName(), cost, formation.getDurationMinutes(), formation.getSpiritEnergyBoost(), formation.getCultivationBonus()));
    }

    public FormationUpgradeResult upgradeFormation(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);

        Formation formation = getActiveFormation(playerId);
        if (formation == null) {
            return FormationUpgradeResult.fail(I18n.t("formation.you_have_not_set_up"));
        }

        if (formation.getLevel() >= Formation.MAX_LEVEL) {
            return FormationUpgradeResult.fail(I18n.t("formation.the_formation_is_already", Formation.MAX_LEVEL));
        }

        int newLevel = formation.getLevel() + 1;
        long cost = Formation.getPlaceCost(formation.getFormationKey(), newLevel);
        long spiritStones = itemService.getSpiritStoneCount(playerId);
        if (spiritStones < cost) {
            return FormationUpgradeResult.fail(I18n.t("formation.not_enough_spirit_stones", cost, spiritStones));
        }

        DatabaseManager.runTransaction(conn -> {
            if (!itemService.removeSpiritStones(conn, playerId, cost)) {
                throw new SQLException("灵石扣除失败");
            }
            FORMATION_DAO.updateFormationStats(conn, formation.getId(), newLevel,
                    Formation.getSpiritEnergyBoostForLevel(formation.getFormationKey(), newLevel),
                    Formation.getCultivationBonusForLevel(formation.getFormationKey(), newLevel),
                    Formation.getDefenseBonusForLevel(formation.getFormationKey(), newLevel),
                    Formation.getHeartDemonResistForLevel(formation.getFormationKey(), newLevel));
            return null;
        });

        return FormationUpgradeResult.ok(I18n.t("formation.upgraded",
                formation.getName(), newLevel, cost), newLevel);
    }

    public OperationResult removeFormation(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);

        Formation formation = getActiveFormation(playerId);
        if (formation == null) {
            return OperationResult.fail(I18n.t("formation.you_have_not_set_up"));
        }

        DatabaseManager.runTransaction(conn -> {
            FORMATION_DAO.deactivate(conn, formation.getId());
            return null;
        });

        return OperationResult.ok(I18n.t("formation.dismantled", formation.getName()));
    }

    public static void tickFormations() {

        // 服务器正在关闭、或测试里刚换了连接池：这时候没有库可查，
        // 继续跑只会刷一屏 "Connection is closed" 把真问题淹掉。
        if (!com.mtxgdn.db.DatabaseManager.isReady()) {
            return;
        }
        // 内置模块门控：阵法模块停用后暂停阵法到期结算
        if (!com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown("formation")) return;
        FORMATION_DAO.expireDueFormations(System.currentTimeMillis());
    }

    public int getTotalSpiritEnergyBoost(long playerId) {
        Formation f = getActiveFormation(playerId);
        return f != null ? f.getSpiritEnergyBoost() : 0;
    }

    public int getTotalCultivationBonus(long playerId) {
        Formation f = getActiveFormation(playerId);
        return f != null ? f.getCultivationBonus() : 0;
    }

    public int getTotalHeartDemonResist(long playerId) {
        Formation f = getActiveFormation(playerId);
        return f != null ? f.getHeartDemonResist() : 0;
    }

    /**
     * 已激活阵法的防御加成（% 减伤）：护山大阵每级 +10%、迷踪阵每级 +5%。
     * 供 PVE / PVP / 团队 Boss 结算按百分比削减受到的伤害，激活期间全程生效。
     */
    public int getTotalDefenseBonus(long playerId) {
        Formation f = getActiveFormation(playerId);
        return f != null ? f.getDefenseBonus() : 0;
    }

    /** 阵图物品 fullKey → 阵法 key；非阵图返回 null。 */
    public static String treasureKeyToFormationKey(String fullKey) {
        return switch (fullKey) {
            case "mtxgdn:spirit_gathering_formation" -> "spirit_gathering";
            case "mtxgdn:mountain_protection_formation" -> "mountain_protection";
            case "mtxgdn:purification_formation" -> "purification";
            default -> null;
        };
    }

    /**
     * 使用阵图物品布置对应 1 级阵法：消耗 1 张阵图（不另扣灵石）。
     * 需要洞府且当前未布置阵法；成功后按该阵法 1 级效果激活。
     */
    public Map<String, Object> placeFormationFromTreasure(long playerId, String treasureFullKey) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();
        String formationKey = treasureKeyToFormationKey(treasureFullKey);
        if (formationKey == null) {
            result.put("success", false);
            result.put("message", I18n.t("formation.this_is_not_a_formation"));
            return result;
        }
        if (com.mtxgdn.common.service.ServiceRegistry.getCaveService().getCaveByPlayerId(playerId) == null) {
            result.put("success", false);
            result.put("message", I18n.t("formation.you_do"));
            return result;
        }
        if (getActiveFormation(playerId) != null) {
            result.put("success", false);
            result.put("message", I18n.t("formation.you_have_already_set_up"));
            return result;
        }
        final int level = 1;
        try {
            DatabaseManager.runTransaction(conn -> {
                // 背包里至少要有一张阵图（没有行时 getItemCount 返回 0，与"查不到行"等价）
                if (itemService.getItemCount(conn, playerId, treasureFullKey) < 1) {
                    throw new SQLException("背包中没有该阵图");
                }
                long now = System.currentTimeMillis();
                long durationMs = (long) Formation.getBaseDuration(formationKey) * 60 * 1000;
                FORMATION_DAO.insertFormation(conn, playerId, formationKey,
                        Formation.getNameForKey(formationKey), level,
                        Formation.getSpiritEnergyBoostForLevel(formationKey, level),
                        Formation.getCultivationBonusForLevel(formationKey, level),
                        Formation.getDefenseBonusForLevel(formationKey, level),
                        Formation.getHeartDemonResistForLevel(formationKey, level),
                        Formation.getBaseDuration(formationKey), now, now + durationMs);
                if (!itemService.removeItem(conn, playerId, treasureFullKey, 1)) {
                    throw new SQLException("阵图扣除失败: " + treasureFullKey);
                }
                return null;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", I18n.t("formation.failed_to_use_the_formation", e.getMessage()));
            return result;
        }
        Formation formation = getActiveFormation(playerId);
        result.put("success", true);
        // 防御/心魔两段都是"有才显示"的尾巴，整体各占一个占位符（{4} / {5}），
        // 没有该属性时传空串。拆成「，防御加成 +{5}%」那种半句模板，
        // 会让英文翻译很难摆对逗号和换行位置。
        result.put("message", I18n.t("formation.use.success",
                formation.getName(),
                formation.getDurationMinutes(),
                formation.getSpiritEnergyBoost(),
                formation.getCultivationBonus(),
                formation.getDefenseBonus() > 0
                        ? "，防御加成 +" + formation.getDefenseBonus() + "%"
                        : "",
                formation.getHeartDemonResist() > 0
                        ? "，心魔抵抗 +" + formation.getHeartDemonResist()
                        : ""));
        result.put("formation", formation);
        return result;
    }
}
