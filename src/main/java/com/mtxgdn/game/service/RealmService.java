package com.mtxgdn.game.service;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.RealmBreakthroughResult;
import com.mtxgdn.game.entity.RealmConfig;
import com.mtxgdn.game.entity.SpiritualRoot;

import java.util.List;
import java.util.Random;

public class RealmService {

    private final PlayerService playerService;
    private final ItemService itemService;
    private final Random random = new Random();

    private static final int[] BASE_SUCCESS_RATES = { 80, 80, 70, 60, 50, 40, 30, 20, 15, 10 };

    private static final long[] BREAKTHROUGH_COOLDOWN_SECONDS = {
            600,   // 凡人: 10分钟
            1800,  // 炼气: 30分钟
            3600,  // 筑基: 1小时
            7200,  // 金丹: 2小时
            14400, // 元婴: 4小时
            28800, // 化神: 8小时
            43200, // 合体: 12小时
            86400, // 大乘: 1天
            172800,// 渡劫: 2天
            259200 // 真仙: 3天
    };

    private static final String[][] TRIBULATION_TYPES = {
        {"雷劫", "九天神雷", "九天之上雷云翻滚，一道水桶粗的紫色天雷当头劈下！"},
        {"心魔劫", "心魔噬魂", "一股黑气自丹田涌出，眼前浮现过往种种执念，心神剧震..."},
        {"风火劫", "风火炼体", "阴风自脚下升起，三昧真火从天灵灌入，风火交加，肉身几欲崩溃！"},
        {"水劫", "玄冥弱水", "天空裂开一道缝隙，玄冥弱水倾泻而下，每一滴重逾千斤，欲将你碾为齑粉！"},
        {"五行劫", "五行颠倒", "金木水火土五行之力同时暴走，天地为之色变，大道规则混乱不堪！"},
        {"阴阳劫", "阴阳逆转", "黑白两道雷霆交织成太极图，缓缓压下，阴阳逆转之力撕扯灵魂！"},
        {"轮回劫", "六道轮回", "眼前浮现六道轮回之门，因果业力化作锁链缠身，欲将你拖入轮回深渊！"},
    };

    private static final int[][] TRIBULATION_REALMS = {
        {0, 1, 2},  // 雷劫: 凡人/炼气/筑基（突破至炼气/筑基/金丹）
        {3},        // 心魔劫: 金丹（突破至元婴）
        {4},        // 风火劫: 元婴（突破至化神）
        {5},        // 水劫: 化神（突破至合体）
        {6},        // 五行劫: 合体（突破至大乘）
        {7},        // 阴阳劫: 大乘（突破至渡劫）
        {8, 9},     // 轮回劫: 渡劫/真仙（突破至真仙/金仙）
    };

    public RealmService(PlayerService playerService) {
        this.playerService = playerService;
        this.itemService = new ItemService();
    }

    public RealmBreakthroughResult tryBreakthrough(long userId) {
        // 使用事务锁防止并发突破
        return com.mtxgdn.db.DatabaseManager.runTransaction(conn -> {
            // 使用 SELECT FOR UPDATE 加悲观锁
            String lockSql = "SELECT * FROM players WHERE user_id = ? " + 
                com.mtxgdn.db.DatabaseManager.getDialect().getForUpdateClause();
            try (java.sql.PreparedStatement ps = conn.prepareStatement(lockSql)) {
                ps.setLong(1, userId);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return failure("玩家不存在");
                    }
                    
                    // 从锁定的结果集中构建玩家对象
                    Player player = mapPlayerFromResultSet(rs);
                    
                    long remainingCooldown = checkBreakthroughCooldown(player);
                    if (remainingCooldown > 0) {
                        String cooldownMsg = formatCooldown(remainingCooldown);
                        return failure("突破冷却中，还需等待 " + cooldownMsg + " 才能再次突破");
                    }

                    RealmConfig currentConfig = GameConfigLoader.getRealmConfig(player.getRealm(), player.getSubRealm());
                    if (currentConfig == null) {
                        return failure("当前境界配置不存在");
                    }

                    if (GameConfigLoader.isMaxRealm(player.getRealm(), player.getSubRealm())) {
                        return failure("已达最高境界，无法继续突破");
                    }

                    RealmConfig nextConfig = GameConfigLoader.getNextRealmConfig(player.getRealm(), player.getSubRealm());
                    if (nextConfig == null) {
                        return failure("已是最高境界");
                    }

                    if (player.getExperience() < nextConfig.getRequiredExp()) {
                        long need = nextConfig.getRequiredExp() - player.getExperience();
                        return failure("经验不足，还需要 " + need + " 经验值");
                    }

                    if (nextConfig.getRequiredSpiritStones() > 0) {
                        long spiritStoneCount = getSpiritStoneCountWithLock(conn, player.getId());
                        if (spiritStoneCount < nextConfig.getRequiredSpiritStones()) {
                            long need = nextConfig.getRequiredSpiritStones() - spiritStoneCount;
                            return failure("灵石不足，还需要 " + need + " 灵石");
                        }
                    }

                    boolean isMajorBreakthrough = nextConfig.getId() > player.getRealm();
                    RealmBreakthroughResult result = new RealmBreakthroughResult();
                    result.setHpAdded(nextConfig.getHpBonus());
                    result.setMpAdded(nextConfig.getMpBonus());
                    result.setAttackAdded(nextConfig.getAttackBonus());
                    result.setDefenseAdded(nextConfig.getDefenseBonus());
                    result.setSpeedAdded(nextConfig.getSpeedBonus());
                    result.setSpiritAdded(nextConfig.getSpiritBonus());
                    result.setUnlockedSkillId(nextConfig.getUnlockSkillId());
                    result.setNewRealmName(nextConfig.getFullName());

                    if (!isMajorBreakthrough) {
                        return applyBreakthroughSuccessWithConn(conn, player, nextConfig, result, 
                            "突破成功！你已晋升为【" + nextConfig.getFullName() + "】", false);
                    }

                    return executeTribulationWithConn(conn, player, nextConfig, result);
                }
            }
        });
    }
    
    private Player mapPlayerFromResultSet(java.sql.ResultSet rs) throws java.sql.SQLException {
        Player player = new Player();
        player.setId(rs.getLong("id"));
        player.setUserId(rs.getLong("user_id"));
        player.setName(rs.getString("name"));
        player.setRealm(rs.getInt("realm"));
        player.setSubRealm(rs.getInt("sub_realm"));
        player.setExperience(rs.getLong("experience"));
        player.setHp(rs.getInt("hp"));
        player.setMaxHp(rs.getInt("max_hp"));
        player.setMp(rs.getInt("mp"));
        player.setMaxMp(rs.getInt("max_mp"));
        player.setAttack(rs.getInt("attack"));
        player.setDefense(rs.getInt("defense"));
        player.setSpeed(rs.getInt("speed"));
        player.setSpirit(rs.getInt("spirit"));
        player.setLastBreakthroughTime(rs.getLong("last_breakthrough_time"));
        
        String rootStr = rs.getString("spiritual_root");
        if (rootStr != null && !rootStr.isEmpty()) {
            try {
                player.setSpiritualRoot(SpiritualRoot.valueOf(rootStr));
            } catch (Exception e) {
                // 忽略无效的灵根值
            }
        }
        return player;
    }
    
    private long getSpiritStoneCountWithLock(java.sql.Connection conn, long playerId) throws java.sql.SQLException {
        String sql = "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ? " +
            com.mtxgdn.db.DatabaseManager.getDialect().getForUpdateClause();
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, "mtxgdn:spirit_stone");
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("quantity");
                }
            }
        }
        return 0;
    }

    private RealmBreakthroughResult executeTribulationWithConn(java.sql.Connection conn, Player player, RealmConfig nextConfig, RealmBreakthroughResult result) throws Exception {
        result.setHasTribulation(true);

        String[] trib = pickTribulationForRealm(player.getRealm());
        String tribName = trib[0];
        String tribSubtitle = trib[1];
        String tribDesc = trib[2];

        result.setTribulationType(tribName);
        result.setTribulationDescription(tribDesc);

        result.addTribulationLog("你开始突破【" + nextConfig.getFullName() + "】...");
        result.addTribulationLog("天地感应，" + tribName + "降临！");
        result.addTribulationLog("——" + tribSubtitle + "——");
        result.addTribulationLog("【" + tribDesc + "】");

        double baseRate = getBaseSuccessRate(player.getRealm());
        result.setBaseSuccessRate(baseRate);
        result.addSuccessRateBreakdown("基础成功率: " + String.format("%.0f", baseRate) + "%");

        double spiritBonus = Math.min(15.0, player.getSpirit() / 50.0);
        if (spiritBonus > 0) {
            result.addSuccessRateBreakdown("灵力加成: +" + String.format("%.0f", spiritBonus) + "% (灵力 " + player.getSpirit() + ")");
        }

        double rootBonus = getSpiritualRootBonus(player.getSpiritualRoot(), player.getRealm());
        if (rootBonus > 0) {
            result.addSuccessRateBreakdown("灵根加成: +" + String.format("%.0f", rootBonus) + "%");
        } else if (rootBonus < 0) {
            result.addSuccessRateBreakdown("灵根减益: " + String.format("%.0f", rootBonus) + "%");
        }

        double statBonus = Math.min(0.10, getTribulationStatBonus(tribName, player));
        if (statBonus > 0) {
            result.addSuccessRateBreakdown(tribName + "对应属性加成: +" + String.format("%.3f", statBonus * 100) + "%");
        }

        double pillBonus = 0;
        if (hasItemWithLock(conn, player.getId(), "mtxgdn:tribulation_pill", 1)) {
            removeItemWithConn(conn, player.getId(), "mtxgdn:tribulation_pill", 1);
            pillBonus = 10.0;
            result.addSuccessRateBreakdown("渡劫丹加成: +10%");
            result.setTribulationItemUsed("tribulation_pill");
            result.addTribulationLog("你服下渡劫丹，体内灵力暴涨，对抗天劫的把握大增！");
        }

        double finalRate = Math.min(95.0, baseRate + spiritBonus + rootBonus + statBonus * 100 + pillBonus);
        result.setFinalSuccessRate(finalRate);

        int roll = random.nextInt(10000);
        result.setRoll(roll);

        result.addTribulationLog("抵抗天劫几率: " + String.format("%.1f", finalRate) + "%");

        boolean success = roll < (int)(finalRate * 100);

        if (success) {
            result.addTribulationLog(tribName + "散去，天穹裂开一道缝隙，金光洒落！");
            result.addTribulationLog("劫后余生，道心更加坚定...");
            return applyBreakthroughSuccessWithConn(conn, player, nextConfig, result,
                    "渡劫成功！你已晋升为【" + nextConfig.getFullName() + "】", true);
        } else {
            long expLoss = nextConfig.getRequiredExp() / 3;
            int hpLoss = player.getHp() > 1 ? player.getHp() - 1 : 0;

            result.setExpPenalty(expLoss);
            result.setHpPenalty(hpLoss);
            result.addTribulationLog(tribName + "太过凶猛，你未能抵挡...");
            result.addTribulationLog("境界突破失败，修为大损！");
            result.addTribulationLog("损失经验 " + expLoss + " 点，身受重伤");

            player.setExperience(Math.max(0, player.getExperience() - expLoss));
            player.setHp(1);

            SpiritualRoot root = player.getSpiritualRoot();
            if (root != null && root.hasEffect(SpiritualRoot.SpecialEffect.LATE_BLOOMER)) {
                int bonus = (int)(3 * root.getEffectValue());
                if (bonus < 1) bonus = 1;
                player.setAttack(player.getAttack() + (int)(bonus * (1 + root.getAttackBonus())));
                player.setDefense(player.getDefense() + (int)(bonus * (1 + root.getDefenseBonus())));
                player.setSpeed(player.getSpeed() + (int)(bonus * (1 + root.getSpeedBonus())));
                result.addTribulationLog("然混沌灵根在劫难中反而淬炼...获得少量属性提升！");
            }

            updatePlayerWithConn(conn, player);

            result.setSuccess(false);
            result.setMessage("渡劫失败！你在" + result.getTribulationType() + "中身受重伤，损失了 " + expLoss + " 点经验");
            return result;
        }
    }
    
    private boolean hasItemWithLock(java.sql.Connection conn, long playerId, String itemKey, long quantity) throws java.sql.SQLException {
        String sql = "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ? " +
            com.mtxgdn.db.DatabaseManager.getDialect().getForUpdateClause();
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("quantity") >= quantity;
                }
            }
        }
        return false;
    }
    
    private void removeItemWithConn(java.sql.Connection conn, long playerId, String itemKey, long quantity) throws java.sql.SQLException {
        String sql = "UPDATE players_items SET quantity = quantity - ?, updated_at = CURRENT_TIMESTAMP WHERE player_id = ? AND item_key = ?";
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, quantity);
            ps.setLong(2, playerId);
            ps.setString(3, itemKey);
            ps.executeUpdate();
        }
    }
    
    private void updatePlayerWithConn(java.sql.Connection conn, Player player) throws java.sql.SQLException {
        String sql = "UPDATE players SET realm = ?, sub_realm = ?, experience = ?, hp = ?, max_hp = ?, " +
                    "mp = ?, max_mp = ?, attack = ?, defense = ?, speed = ?, spirit = ?, " +
                    "last_breakthrough_time = ? WHERE id = ?";
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, player.getRealm());
            ps.setInt(2, player.getSubRealm());
            ps.setLong(3, player.getExperience());
            ps.setInt(4, player.getHp());
            ps.setInt(5, player.getMaxHp());
            ps.setInt(6, player.getMp());
            ps.setInt(7, player.getMaxMp());
            ps.setInt(8, player.getAttack());
            ps.setInt(9, player.getDefense());
            ps.setInt(10, player.getSpeed());
            ps.setInt(11, player.getSpirit());
            ps.setLong(12, player.getLastBreakthroughTime());
            ps.setLong(13, player.getId());
            ps.executeUpdate();
        }
    }

    private RealmBreakthroughResult executeTribulation(Player player, RealmConfig nextConfig, RealmBreakthroughResult result) {
        result.setHasTribulation(true);

        String[] trib = pickTribulationForRealm(player.getRealm());
        String tribName = trib[0];
        String tribSubtitle = trib[1];
        String tribDesc = trib[2];

        result.setTribulationType(tribName);
        result.setTribulationDescription(tribDesc);

        result.addTribulationLog("你开始突破【" + nextConfig.getFullName() + "】...");
        result.addTribulationLog("天地感应，" + tribName + "降临！");
        result.addTribulationLog("——" + tribSubtitle + "——");
        result.addTribulationLog("【" + tribDesc + "】");

        double baseRate = getBaseSuccessRate(player.getRealm());
        result.setBaseSuccessRate(baseRate);
        result.addSuccessRateBreakdown("基础成功率: " + String.format("%.0f", baseRate) + "%");

        double spiritBonus = Math.min(15.0, player.getSpirit() / 50.0);
        if (spiritBonus > 0) {
            result.addSuccessRateBreakdown("灵力加成: +" + String.format("%.0f", spiritBonus) + "% (灵力 " + player.getSpirit() + ")");
        }

        double rootBonus = getSpiritualRootBonus(player.getSpiritualRoot(), player.getRealm());
        if (rootBonus > 0) {
            result.addSuccessRateBreakdown("灵根加成: +" + String.format("%.0f", rootBonus) + "%");
        } else if (rootBonus < 0) {
            result.addSuccessRateBreakdown("灵根减益: " + String.format("%.0f", rootBonus) + "%");
        }

        // 天劫对应属性加成（上限 10%，防止后期属性爆炸导致成功率恒顶 95%）
        double statBonus = Math.min(0.10, getTribulationStatBonus(tribName, player));
        if (statBonus > 0) {
            result.addSuccessRateBreakdown(tribName + "对应属性加成: +" + String.format("%.3f", statBonus * 100) + "%");
        }

        double pillBonus = 0;
        if (itemService.hasItem(player.getId(), "mtxgdn:tribulation_pill", 1)) {
            itemService.removeItem(player.getId(), "mtxgdn:tribulation_pill", 1);
            pillBonus = 10.0;
            result.addSuccessRateBreakdown("渡劫丹加成: +10%");
            result.setTribulationItemUsed("tribulation_pill");
            result.addTribulationLog("你服下渡劫丹，体内灵力暴涨，对抗天劫的把握大增！");
        }

        double finalRate = Math.min(95.0, baseRate + spiritBonus + rootBonus + statBonus * 100 + pillBonus);
        result.setFinalSuccessRate(finalRate);

        int roll = random.nextInt(10000);
        result.setRoll(roll);

        result.addTribulationLog("抵抗天劫几率: " + String.format("%.1f", finalRate) + "%");

        boolean success = roll < (int)(finalRate * 100);

        if (success) {
            result.addTribulationLog(tribName + "散去，天穹裂开一道缝隙，金光洒落！");
            result.addTribulationLog("劫后余生，道心更加坚定...");
            return applyBreakthroughSuccess(player, nextConfig, result,
                    "渡劫成功！你已晋升为【" + nextConfig.getFullName() + "】", true);
        } else {
            long expLoss = nextConfig.getRequiredExp() / 3;
            int hpLoss = player.getHp() > 1 ? player.getHp() - 1 : 0;

            result.setExpPenalty(expLoss);
            result.setHpPenalty(hpLoss);
            result.addTribulationLog(tribName + "太过凶猛，你未能抵挡...");
            result.addTribulationLog("境界突破失败，修为大损！");
            result.addTribulationLog("损失经验 " + expLoss + " 点，身受重伤");

            player.setExperience(Math.max(0, player.getExperience() - expLoss));
            player.setHp(1);

            SpiritualRoot root = player.getSpiritualRoot();
            if (root != null && root.hasEffect(SpiritualRoot.SpecialEffect.LATE_BLOOMER)) {
                int bonus = (int)(3 * root.getEffectValue());
                if (bonus < 1) bonus = 1;
                player.setAttack(player.getAttack() + (int)(bonus * (1 + root.getAttackBonus())));
                player.setDefense(player.getDefense() + (int)(bonus * (1 + root.getDefenseBonus())));
                player.setSpeed(player.getSpeed() + (int)(bonus * (1 + root.getSpeedBonus())));
                result.addTribulationLog("然混沌灵根在劫难中反而淬炼...获得少量属性提升！");
            }

            playerService.updatePlayer(player.getId(), player);
            result.setSuccess(false);
            result.setMessage("渡劫失败！你在" + result.getTribulationType() + "中身受重伤，损失了 " + expLoss + " 点经验");
            return result;
        }
    }

    private RealmBreakthroughResult applyBreakthroughSuccessWithConn(java.sql.Connection conn, Player player, RealmConfig nextConfig,
                                                               RealmBreakthroughResult result, String message,
                                                               boolean majorBreakthrough) throws Exception {
        player.setRealm(nextConfig.getId());
        player.setSubRealm(nextConfig.getSubRealm());
        player.setMaxHp(player.getMaxHp() + nextConfig.getHpBonus());
        player.setHp(player.getMaxHp());
        player.setMaxMp(player.getMaxMp() + nextConfig.getMpBonus());
        player.setMp(player.getMaxMp());
        player.setAttack(player.getAttack() + nextConfig.getAttackBonus());
        player.setDefense(player.getDefense() + nextConfig.getDefenseBonus());
        player.setSpeed(player.getSpeed() + nextConfig.getSpeedBonus());
        player.setSpirit(player.getSpirit() + nextConfig.getSpiritBonus());
        player.setExperience(player.getExperience() - nextConfig.getRequiredExp());
        player.setLastBreakthroughTime(System.currentTimeMillis());

        SpiritualRoot root = player.getSpiritualRoot();
        if (root != null && root.hasEffect(SpiritualRoot.SpecialEffect.LATE_BLOOMER)) {
            int bonus = (int)(5 * root.getEffectValue());
            if (bonus < 1) bonus = 1;
            player.setAttack(player.getAttack() + (int)(bonus * (1 + root.getAttackBonus())));
            player.setDefense(player.getDefense() + (int)(bonus * (1 + root.getDefenseBonus())));
            player.setSpeed(player.getSpeed() + (int)(bonus * (1 + root.getSpeedBonus())));
        }

        updatePlayerWithConn(conn, player);

        if (nextConfig.getRequiredSpiritStones() > 0) {
            removeItemWithConn(conn, player.getId(), "mtxgdn:spirit_stone", nextConfig.getRequiredSpiritStones());
        }

        // 大境界突破成功授予「破天者」称号 + 集中检查境界/战力类称号（不朽尊者/逆天者/大道之主/混沌帝尊/剑道宗师）
        if (majorBreakthrough) {
            try {
                new com.mtxgdn.game.service.TitleService().grantTitle(player.getId(), "sky_breaker");
            } catch (Exception ignored) {
                // 称号授予失败不影响突破
            }
        }
        try {
            new com.mtxgdn.game.service.TitleService().checkAndGrantTitles(player);
        } catch (Exception ignored) {
        }

        result.setSuccess(true);
        result.setMessage(message);
        return result;
    }

    private RealmBreakthroughResult applyBreakthroughSuccess(Player player, RealmConfig nextConfig,
                                                              RealmBreakthroughResult result, String message,
                                                              boolean majorBreakthrough) {
        player.setRealm(nextConfig.getId());
        player.setSubRealm(nextConfig.getSubRealm());
        player.setMaxHp(player.getMaxHp() + nextConfig.getHpBonus());
        player.setHp(player.getMaxHp());
        player.setMaxMp(player.getMaxMp() + nextConfig.getMpBonus());
        player.setMp(player.getMaxMp());
        player.setAttack(player.getAttack() + nextConfig.getAttackBonus());
        player.setDefense(player.getDefense() + nextConfig.getDefenseBonus());
        player.setSpeed(player.getSpeed() + nextConfig.getSpeedBonus());
        player.setSpirit(player.getSpirit() + nextConfig.getSpiritBonus());
        player.setExperience(player.getExperience() - nextConfig.getRequiredExp());
        player.setLastBreakthroughTime(System.currentTimeMillis());

        SpiritualRoot root = player.getSpiritualRoot();
        if (root != null && root.hasEffect(SpiritualRoot.SpecialEffect.LATE_BLOOMER)) {
            int bonus = (int)(5 * root.getEffectValue());
            if (bonus < 1) bonus = 1;
            player.setAttack(player.getAttack() + (int)(bonus * (1 + root.getAttackBonus())));
            player.setDefense(player.getDefense() + (int)(bonus * (1 + root.getDefenseBonus())));
            player.setSpeed(player.getSpeed() + (int)(bonus * (1 + root.getSpeedBonus())));
        }

        playerService.updatePlayer(player.getId(), player);

        if (nextConfig.getRequiredSpiritStones() > 0) {
            itemService.removeSpiritStones(player.getId(), nextConfig.getRequiredSpiritStones());
        }

        // 大境界突破成功授予「破天者」称号 + 集中检查境界/战力类称号（不朽尊者/逆天者/大道之主/混沌帝尊/剑道宗师）
        if (majorBreakthrough) {
            try {
                new com.mtxgdn.game.service.TitleService().grantTitle(player.getId(), "sky_breaker");
            } catch (Exception ignored) {
                // 称号授予失败不影响突破
            }
        }
        try {
            new com.mtxgdn.game.service.TitleService().checkAndGrantTitles(player);
        } catch (Exception ignored) {
        }

        result.setSuccess(true);
        result.setMessage(message);
        return result;
    }

    private double getBaseSuccessRate(int currentRealm) {
        if (currentRealm >= 0 && currentRealm < BASE_SUCCESS_RATES.length) {
            return BASE_SUCCESS_RATES[currentRealm];
        }
        return 80.0;
    }

    private double getSpiritualRootBonus(SpiritualRoot root, int currentRealm) {
        if (root == null) return 0;
        return switch (root.getTier()) {
            case PRIMORDIAL -> 35.0;
            case PERFECT -> 25.0;
            case HEAVENLY -> 15.0;
            case VARIANT -> 10.0;
            case DUAL -> 5.0;
            case TRIPLE -> 0;
            case QUAD -> -5.0;
            case MIXED -> root.hasEffect(SpiritualRoot.SpecialEffect.LATE_BLOOMER) && currentRealm >= 4 ? 15.0 : -10.0;
        };
    }

    private double getTribulationStatBonus(String tribulationType, Player player) {
        return switch (tribulationType) {
            case "雷劫" -> player.getDefense() / 500.0;
            case "心魔劫" -> player.getSpirit() / 400.0;
            case "风火劫" -> player.getSpeed() / 300.0;
            case "水劫" -> player.getDefense() / 350.0;
            case "五行劫" -> (player.getAttack() + player.getDefense() + player.getSpirit()) / 1200.0;
            case "阴阳劫" -> (player.getSpirit() * 2 + player.getDefense()) / 900.0;
            case "轮回劫" -> player.getSpirit() / 300.0;
            default -> 0;
        };
    }

    private String[] pickTribulationForRealm(int realm) {
        List<String[]> available = new java.util.ArrayList<>();
        for (int i = 0; i < TRIBULATION_TYPES.length; i++) {
            for (int r : TRIBULATION_REALMS[i]) {
                if (r == realm) {
                    available.add(TRIBULATION_TYPES[i]);
                    break;
                }
            }
        }
        if (available.isEmpty()) {
            return TRIBULATION_TYPES[random.nextInt(TRIBULATION_TYPES.length)];
        }
        return available.get(random.nextInt(available.size()));
    }

    private long checkBreakthroughCooldown(Player player) {
        long lastBreakthrough = player.getLastBreakthroughTime();
        if (lastBreakthrough <= 0) {
            return 0;
        }

        int realmIndex = player.getRealm();
        if (realmIndex >= BREAKTHROUGH_COOLDOWN_SECONDS.length) {
            realmIndex = BREAKTHROUGH_COOLDOWN_SECONDS.length - 1;
        }

        long cooldownSeconds = BREAKTHROUGH_COOLDOWN_SECONDS[realmIndex];
        long now = System.currentTimeMillis();
        long elapsedSeconds = (now - lastBreakthrough) / 1000;

        return Math.max(0, cooldownSeconds - elapsedSeconds);
    }

    private String formatCooldown(long remainingSeconds) {
        if (remainingSeconds < 60) {
            return remainingSeconds + "秒";
        } else if (remainingSeconds < 3600) {
            return (remainingSeconds / 60) + "分钟";
        } else if (remainingSeconds < 86400) {
            long hours = remainingSeconds / 3600;
            long minutes = (remainingSeconds % 3600) / 60;
            return hours + "小时" + (minutes > 0 ? minutes + "分钟" : "");
        } else {
            long days = remainingSeconds / 86400;
            long hours = (remainingSeconds % 86400) / 3600;
            return days + "天" + (hours > 0 ? hours + "小时" : "");
        }
    }

    private RealmBreakthroughResult failure(String message) {
        RealmBreakthroughResult result = new RealmBreakthroughResult();
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }
}
