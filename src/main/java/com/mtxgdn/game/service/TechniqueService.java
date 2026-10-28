package com.mtxgdn.game.service;

import com.mtxgdn.common.result.OperationResult;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.TechniqueDAO;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Technique;
import com.mtxgdn.game.config.TechniqueDefaults;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.util.GameLogger;

import java.sql.SQLException;
import java.util.List;

/**
 * 功法系统的业务入口。
 *
 * <p>裸 SQL 已全部抽到 {@link TechniqueDAO}：这个类只负责业务规则 ——
 * 学习/装备/卸下/升级的前置校验、灵石与金币的扣费顺序、装备后属性加成的
 * 施加与回退、以及出厂功法数据的补齐。</p>
 *
 * <p><b>事务边界</b>：扣钱与写 {@code players_techniques} 必须同生共死，
 * 所以这些写操作一律走 DAO 的 {@code Connection} 重载，在
 * {@link DatabaseManager#runTransaction} 的 lambda 内执行；
 * DAO 自己不会另开连接，否则 SQLite 下会撞 {@code SQLITE_BUSY_SNAPSHOT}。</p>
 */
public class TechniqueService {

    private static final GameLogger LOG = GameLogger.getLogger(TechniqueService.class);
    private static final int MAX_EQUIPPED = 3;

    /** {@code techniques} / {@code players_techniques} 的读写入口（无状态，可共享）。 */
    private static final TechniqueDAO TECHNIQUE_DAO = new TechniqueDAO();

    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();

    public Technique getTechniqueById(long techniqueId) {
        return TECHNIQUE_DAO.findById(techniqueId);
    }

    public List<Technique> getAllTechniques() {
        return TECHNIQUE_DAO.findAll();
    }

    public List<Technique> getPlayerTechniques(long playerId) {
        return TECHNIQUE_DAO.findPlayerTechniques(playerId);
    }

    public List<Technique> getEquippedTechniques(long playerId) {
        return TECHNIQUE_DAO.findEquipped(playerId);
    }

    public boolean hasTechnique(long playerId, long techniqueId) {
        return TECHNIQUE_DAO.hasTechnique(playerId, techniqueId);
    }

    /** 已装备功法的修炼速度加成之和（供修炼结算使用）。 */
    public double getEquippedCultivationSpeedBonus(long playerId) {
        double total = 0;
        for (Technique t : TECHNIQUE_DAO.findEquipped(playerId)) {
            total += t.getScaledCultivationSpeedBonus();
        }
        return total;
    }

    /** 已装备功法的经验加成之和（exp_bonus，供 addExperience 全链路结算使用）。 */
    public double getEquippedExpBonus(long playerId) {
        double total = 0;
        for (Technique t : TECHNIQUE_DAO.findEquipped(playerId)) {
            total += t.getScaledExpBonus();
        }
        return total;
    }

    /** 已装备功法的战斗伤害加成之和（combat_damage_bonus，供 PVE/PVP/团队 Boss 结算使用）。 */
    public double getEquippedCombatDamageBonus(long playerId) {
        double total = 0;
        for (Technique t : TECHNIQUE_DAO.findEquipped(playerId)) {
            total += t.getScaledCombatDamageBonus();
        }
        return total;
    }

    /** 已装备功法的减伤之和（damage_reduction，供承伤结算使用）。 */
    public double getEquippedDamageReduction(long playerId) {
        double total = 0;
        for (Technique t : TECHNIQUE_DAO.findEquipped(playerId)) {
            total += t.getScaledDamageReduction();
        }
        return total;
    }

    public OperationResult learnTechnique(long playerId, long techniqueId) {
        Technique technique = TECHNIQUE_DAO.findById(techniqueId);
        if (technique == null) {
            return OperationResult.fail(I18n.t("technique.technique_does_not_exist"));
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            return OperationResult.fail(I18n.t("technique.character_does_not_exist"));
        }

        if (TECHNIQUE_DAO.hasTechnique(playerId, techniqueId)) {
            return OperationResult.fail(I18n.t("technique.you_have_already_learned", technique.getName()));
        }

        if (player.getRealm() < technique.getRequiredRealm()) {
            return OperationResult.fail(I18n.t("technique.your_realm_is_too_low", technique.getName()));
        }

        if (player.getGold() < technique.getLearnCostGold()) {
            return OperationResult.fail(I18n.t("technique.not_enough_gold", technique.getLearnCostGold()));
        }

        if (itemService.getSpiritStoneCount(playerId) < technique.getLearnCostSpiritStones()) {
            return OperationResult.fail(I18n.t("technique.not_enough_spirit_stones", technique.getLearnCostSpiritStones()));
        }

        try {
            DatabaseManager.runTransaction(conn -> {
                playerService.addGold(conn, playerId, -technique.getLearnCostGold());
                if (!itemService.removeSpiritStones(conn, playerId, technique.getLearnCostSpiritStones())) {
                    throw new SQLException("灵石扣除失败");
                }

                TECHNIQUE_DAO.insertPlayerTechnique(conn, playerId, techniqueId);
                return null;
            });
        } catch (Exception e) {
            return OperationResult.fail(I18n.t("technique.failed_to_learn_the_technique", e.getMessage()));
        }

        // 学习不直接加属性；属性只在「装备」时生效（避免重复叠加）

        return OperationResult.ok(I18n.t("technique.successfully_learned_the_technique", technique.getName()));
    }

    public OperationResult equipTechnique(long playerId, long techniqueId) {
        if (!TECHNIQUE_DAO.hasTechnique(playerId, techniqueId)) {
            return OperationResult.fail(I18n.t("technique.you_have_not_learned"));
        }
        Technique tech = TECHNIQUE_DAO.findPlayerTechniqueDetail(playerId, techniqueId);
        if (tech == null) {
            return OperationResult.fail(I18n.t("technique.you_have_not_learned"));
        }
        if (tech.isEquipped()) {
            return OperationResult.fail(I18n.t("technique.is_already_being_circulated", tech.getName()));
        }
        if (TECHNIQUE_DAO.countEquipped(playerId) >= MAX_EQUIPPED) {
            return OperationResult.fail(I18n.t("technique.you_can_circulate_at_most", MAX_EQUIPPED));
        }

        TECHNIQUE_DAO.setEquipped(playerId, techniqueId, true);

        Player player = playerService.getPlayerById(playerId);
        applyTechniqueBonuses(player, tech);
        playerService.updatePlayer(playerId, player);

        return OperationResult.ok(I18n.t("technique.started_circulating_the_technique", tech.getName()));
    }

    public OperationResult unequipTechnique(long playerId, long techniqueId) {
        if (!TECHNIQUE_DAO.hasTechnique(playerId, techniqueId)) {
            return OperationResult.fail(I18n.t("technique.you_have_not_learned"));
        }
        Technique tech = TECHNIQUE_DAO.findPlayerTechniqueDetail(playerId, techniqueId);
        if (tech == null) {
            return OperationResult.fail(I18n.t("technique.you_have_not_learned"));
        }
        if (!tech.isEquipped()) {
            return OperationResult.fail(I18n.t("technique.is_not_being_circulated", tech.getName()));
        }

        TECHNIQUE_DAO.setEquipped(playerId, techniqueId, false);

        // 卸下时回退属性加成，防止反复装卸无限叠加
        Player player = playerService.getPlayerById(playerId);
        removeTechniqueBonuses(player, tech);
        playerService.updatePlayer(playerId, player);

        return OperationResult.ok(I18n.t("technique.stopped_circulating_the_technique", tech.getName()));
    }

    public OperationResult upgradeTechnique(long playerId, long techniqueId) {
        if (!TECHNIQUE_DAO.hasTechnique(playerId, techniqueId)) {
            return OperationResult.fail(I18n.t("technique.you_have_not_learned"));
        }
        Technique tech = TECHNIQUE_DAO.findPlayerTechniqueDetail(playerId, techniqueId);
        if (tech == null) {
            return OperationResult.fail(I18n.t("technique.you_have_not_learned"));
        }
        if (tech.getLevel() >= tech.getMaxLevel()) {
            return OperationResult.fail(I18n.t("technique.has_reached_its_maximum_level", tech.getName()));
        }

        int costGold = tech.getUpgradeBaseCostGold() * tech.getLevel();
        int costSS = tech.getUpgradeBaseCostSpiritStones() * tech.getLevel();

        Player player = playerService.getPlayerById(playerId);
        if (player.getGold() < costGold) {
            return OperationResult.fail(I18n.t("technique.not_enough_gold_2", costGold));
        }
        long ssCount = itemService.getSpiritStoneCount(playerId);
        if (ssCount < costSS) {
            return OperationResult.fail(I18n.t("technique.not_enough_spirit_stones_2", costSS));
        }

        try {
            DatabaseManager.runTransaction(conn -> {
                playerService.addGold(conn, playerId, -costGold);
                if (!itemService.removeSpiritStones(conn, playerId, costSS)) {
                    throw new SQLException("灵石扣除失败");
                }

                TECHNIQUE_DAO.levelUp(conn, playerId, techniqueId);
                return null;
            });
        } catch (Exception e) {
            return OperationResult.fail(I18n.t("technique.failed_to_upgrade_the_technique", e.getMessage()));
        }

        // 已装备的功法升级后，按新旧等级补差属性（先回退旧加成，再施加新加成）
        if (tech.isEquipped()) {
            removeTechniqueBonuses(player, tech);
            Technique upgraded = TECHNIQUE_DAO.findPlayerTechniqueDetail(playerId, techniqueId);
            applyTechniqueBonuses(player, upgraded);
            playerService.updatePlayer(playerId, player);
        }

        return OperationResult.ok(I18n.t("technique.level_up", tech.getName(), tech.getLevel() + 1));
    }

    public void addProficiency(long playerId, long techniqueId, int amount) {
        TECHNIQUE_DAO.addProficiency(playerId, techniqueId, amount);
    }

    /**
     * 补齐出厂功法数据。
     *
     * <p>按名称幂等：已存在的功法跳过，所以旧库可以增量补进新功法。
     * 数据本体在 {@link TechniqueDefaults}，这里只做编排。</p>
     */
    public void insertDefaultTechniques() {
        int inserted = 0;
        for (Object[] row : TechniqueDefaults.ROWS) {
            String name = (String) row[0];
            if (TECHNIQUE_DAO.existsByName(name)) {
                continue;
            }
            TECHNIQUE_DAO.insertDefinition(
                    name,
                    (String) row[1],
                    ((Number) row[2]).intValue(),
                    ((Number) row[3]).longValue(),
                    ((Number) row[4]).longValue(),
                    ((Number) row[5]).intValue(),
                    ((Number) row[6]).intValue(),
                    (String) row[7],
                    ((Number) row[8]).intValue(),
                    ((Number) row[9]).intValue(),
                    ((Number) row[10]).intValue(),
                    ((Number) row[11]).intValue(),
                    ((Number) row[12]).intValue(),
                    ((Number) row[13]).intValue(),
                    ((Number) row[14]).intValue(),
                    ((Number) row[15]).doubleValue(),
                    ((Number) row[16]).doubleValue(),
                    ((Number) row[17]).doubleValue(),
                    ((Number) row[18]).doubleValue());
            inserted++;
        }
        if (inserted > 0) {
            LOG.info("已初始化默认功法数据");
        }
    }

    private void applyTechniqueBonuses(Player player, Technique technique) {
        if (technique == null) return;
        player.setMaxHp(player.getMaxHp() + technique.getScaledHpBonus());
        player.setHp(Math.min(player.getHp() + technique.getScaledHpBonus(), player.getMaxHp()));
        player.setMaxMp(player.getMaxMp() + technique.getScaledMpBonus());
        player.setMp(Math.min(player.getMp() + technique.getScaledMpBonus(), player.getMaxMp()));
        player.setAttack(player.getAttack() + technique.getScaledAttackBonus());
        player.setDefense(player.getDefense() + technique.getScaledDefenseBonus());
        player.setSpeed(player.getSpeed() + technique.getScaledSpeedBonus());
        player.setSpirit(player.getSpirit() + technique.getScaledSpiritBonus());
    }

    /** 卸下功法时回退对应属性加成（与 applyTechniqueBonuses 严格互逆）。 */
    private void removeTechniqueBonuses(Player player, Technique technique) {
        if (technique == null) return;
        player.setMaxHp(Math.max(0, player.getMaxHp() - technique.getScaledHpBonus()));
        player.setMaxMp(Math.max(0, player.getMaxMp() - technique.getScaledMpBonus()));
        player.setAttack(Math.max(0, player.getAttack() - technique.getScaledAttackBonus()));
        player.setDefense(Math.max(0, player.getDefense() - technique.getScaledDefenseBonus()));
        player.setSpeed(Math.max(0, player.getSpeed() - technique.getScaledSpeedBonus()));
        player.setSpirit(Math.max(0, player.getSpirit() - technique.getScaledSpiritBonus()));
    }
}
