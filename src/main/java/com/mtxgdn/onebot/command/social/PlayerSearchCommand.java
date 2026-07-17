package com.mtxgdn.onebot.command.social;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.List;

public class PlayerSearchCommand extends Command {

    private static final int MAX_RESULTS = 10;

    public PlayerSearchCommand() {
        super(new String[]{"查人", "查找", "search", "playersearch"},
                "根据名称搜索玩家",
                "/查人 <名称>",
                "社交",
                null);
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        String keyword = ctx.getArg().trim();
        if (keyword.isEmpty()) {
            ctx.reply("用法: /查人 <名称>\n例: /查人 李白");
            return;
        }

        var playerService = ServiceRegistry.getPlayerService();
        List<PlayerInfo> results = playerService.searchPlayersByName(keyword, MAX_RESULTS, 0);

        if (results.isEmpty()) {
            ctx.reply("未找到名称包含「" + keyword + "」的玩家。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🔍 搜索「").append(keyword).append("」结果:\n");
        for (int i = 0; i < results.size(); i++) {
            PlayerInfo fp = results.get(i);
            String realmName = fp.getRealmName() != null ? fp.getRealmName() : "凡人";
            String rootName = fp.getSpiritualRoot() != null ? fp.getSpiritualRoot().getDisplayName() : "无";
            sb.append(i + 1).append(". ").append(fp.getName())
              .append(" | Lv.").append(fp.getLevel())
              .append(" | ").append(realmName)
              .append("\n  ")
              .append("灵根: ").append(rootName)
              .append(" | 攻击: ").append(fp.getAttack())
              .append(" | 防御: ").append(fp.getDefense())
              .append("\n  ")
              .append("金币: ").append(fp.getGold())
              .append(" | ").append(fp.isCultivating() ? "⏳修炼中" : "空闲")
              .append("\n");
        }
        if (results.size() >= MAX_RESULTS) {
            sb.append("\n...结果过多，请尝试更精确的名称");
        }
        ctx.reply(sb.toString());
    }
}
