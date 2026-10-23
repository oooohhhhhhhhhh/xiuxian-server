package com.mtxgdn.onebot.command.cultivation;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.game.service.TitleService;
import com.mtxgdn.common.service.ServiceRegistry;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import com.mtxgdn.i18n.I18n;

/**
 * 转世系统（终局玩法）：金仙满级后自碎道基、转世重修。
 * 保留 30% 当前属性作为"前世遗泽"永久加成，保留背包/功法/技能/称号，
 * 获得「逆天者」称号，重新从凡人开始攀登——多周目循环目标。
 */
public class ReincarnateCommand extends Command {

    /** 金仙境界 id */
    private static final int MAX_REALM = 10;
    /** 转世保留的属性比例 */
    private static final double KEEP_STAT_RATIO = 0.30;

    public ReincarnateCommand() {
        super(new String[]{"转世", "reincarnate"},
                "金仙满级后转世重修，保留三成修为重新攀登",
                "/转世",
                "修炼",
                "game.player.info");
        
        setI18nKeys("cmd.reincarnate.desc", "cmd.reincarnate.usage");
    setModule("cultivation");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        PlayerService playerService = ServiceRegistry.getPlayerService();
        Player player = playerService.getPlayerByUserIdRaw(userId);
        if (player == null) {
            ctx.reply(I18n.t("cultivation.character_not_found"));
            return;
        }

        if (player.getRealm() < MAX_REALM) {
            ctx.reply(I18n.t("cultivation.reincarnation_is_only_possible_after"));
            return;
        }

        int attack = player.getAttack();
        int defense = player.getDefense();
        int speed = player.getSpeed();
        int spirit = player.getSpirit();
        int maxHp = player.getMaxHp();
        int maxMp = player.getMaxMp();

        try {
            DatabaseManager.runTransaction(conn -> {
                String sql = """
                    UPDATE players SET realm = 0, sub_realm = 0, experience = 0,
                        hp = ?, max_hp = ?, mp = ?, max_mp = ?, attack = ?, defense = ?, speed = ?, spirit = ?,
                        current_location_id = 1, last_breakthrough_time = 0
                    WHERE id = ?
                    """;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int keepHp = Math.max(1, (int)(maxHp * KEEP_STAT_RATIO));
                    int keepMp = Math.max(1, (int)(maxMp * KEEP_STAT_RATIO));
                    int keepAtk = Math.max(1, (int)(attack * KEEP_STAT_RATIO));
                    int keepDef = Math.max(1, (int)(defense * KEEP_STAT_RATIO));
                    int keepSpd = Math.max(1, (int)(speed * KEEP_STAT_RATIO));
                    int keepSpi = Math.max(1, (int)(spirit * KEEP_STAT_RATIO));
                    // 保留 30% 属性作为前世遗泽（hp/mp 取保留值，当前血量同步）
                    ps.setInt(1, keepHp);
                    ps.setInt(2, keepHp);
                    ps.setInt(3, keepMp);
                    ps.setInt(4, keepMp);
                    ps.setInt(5, keepAtk);
                    ps.setInt(6, keepDef);
                    ps.setInt(7, keepSpd);
                    ps.setInt(8, keepSpi);
                    ps.setLong(9, player.getId());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            ctx.reply(I18n.t("cultivation.reincarnation_failed"));
            return;
        }

        // 授予「逆天者」称号
        try {
            ServiceRegistry.getTitleService().grantTitle(player.getId(), "heaven_defier");
        } catch (Exception ignored) {
        }

        ctx.reply(I18n.t("cultivation.reincarnation_nyou_shatter_your_own", Math.max(1, (int)(attack * KEEP_STAT_RATIO)), Math.max(1, (int)(defense * KEEP_STAT_RATIO)), Math.max(1, (int)(speed * KEEP_STAT_RATIO)), Math.max(1, (int)(spirit * KEEP_STAT_RATIO))));
    }
}
