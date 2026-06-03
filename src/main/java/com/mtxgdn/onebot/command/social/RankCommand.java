package com.mtxgdn.onebot.command.social;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import java.util.List;

public class RankCommand extends Command {
    public RankCommand() { super(new String[]{"rank", "排行", "rank2", "排行榜"}, "查看排行榜", "/排行榜 [power|wealth]", "世界", "game.rank.view"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding(); if (userId == null) return;
        if (!ctx.checkPermission("game.rank.view")) return;
        String type = ctx.getArg().trim().toLowerCase();
        var playerService = ServiceRegistry.getPlayerService();
        List<PlayerInfo> players;
        String title;
        switch (type) {
            case "power", "战力": players = playerService.getTopByPower(10); title = "战力排行榜"; break;
            case "wealth", "财富": players = playerService.getTopByWealth(10); title = "财富排行榜"; break;
            default: players = playerService.getTopByRealm(10); title = "境界排行榜"; break;
        }
        StringBuilder sb = new StringBuilder(); sb.append("===== ").append(title).append(" =====\n");
        sb.append("排名  玩家              境界\n");
        int rank = 1;
        for (PlayerInfo p : players) {
            String realmName = p.getRealmName() != null ? p.getRealmName() : "凡人";
            String name = p.getName();
            if (name.length() < 8) { name = name + "                ".substring(0, 8 - name.length()); }
            sb.append(String.format("%-5d %-18s %s\n", rank++, name, realmName));
        }
        if (type.equals("power")) { sb.append("\n---\n战力 = 攻击 + 防御 + 速度 + 生命上限"); }
        else if (type.equals("wealth")) { sb.append("\n---\n财富 = 金币 + 灵石"); }
        ctx.reply(sb.toString());
    }
}

