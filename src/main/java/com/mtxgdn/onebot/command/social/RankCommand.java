package com.mtxgdn.onebot.command.social;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;

public class RankCommand extends Command {

    public RankCommand() {
        super(new String[]{"排行", "排行榜", "rank", "rank2"},
                "查看排行榜",
                "/排行榜 [power|wealth|境界|战力]",
                "社交",
                null,
                false,
                true);
        setModule("rank");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        String type = ctx.getArg().trim().toLowerCase();

        var playerService = new PlayerService();
        List<PlayerInfo> players;
        String title;
        switch (type) {
            case "power", "战力":
                players = playerService.getTopByPower(10);
                title = "战力排行榜";
                break;
            case "wealth", "财富":
                players = playerService.getTopByWealth(10);
                title = "财富排行榜";
                break;
            default:
                players = playerService.getTopByRealm(10);
                title = "境界排行榜";
                break;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== ").append(title).append(" =====\n");
        sb.append("排名  玩家              境界\n");
        int rank = 1;
        for (PlayerInfo player : players) {
            String realmName = player.getRealmName() != null ? player.getRealmName() : "凡人";
            String name = player.getName();
            if (name.length() < 8) {
                name = name + "                ".substring(0, 8 - name.length());
            }
            sb.append(String.format("%-5d %-18s %s\n", rank++, name, realmName));
        }
        if ("power".equals(type) || "战力".equals(type)) {
            sb.append("\n---\n战力 = 攻击 + 防御 + 速度 + 生命上限");
        } else if ("wealth".equals(type) || "财富".equals(type)) {
            sb.append("\n---\n财富 = 金币 + 灵石");
        }
        ctx.reply(sb.toString());
    }
}