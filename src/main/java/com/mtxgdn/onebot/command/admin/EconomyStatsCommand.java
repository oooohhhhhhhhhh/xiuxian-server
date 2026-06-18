package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.Map;

/**
 * 管理员查看全服经济数据面板。
 */
public class EconomyStatsCommand extends Command {
    public EconomyStatsCommand() {
        super(new String[]{"经济", "economy"}, "查看全服经济数据", "/经济", "管理", "admin.economy");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        if (!ctx.checkPermission("admin.economy")) return;

        var eco = ServiceRegistry.getEconomyService();
        Map<String, Object> stats = eco.getEconomyStats();

        StringBuilder sb = new StringBuilder();
        sb.append("📊 全服经济面板\n");
        sb.append("────────────────\n");
        sb.append("全服灵石总量: ").append(formatNum(stats.get("totalStones"))).append("\n");
        sb.append("全服金币总量: ").append(formatNum(stats.get("totalGold"))).append("\n");
        sb.append("玩家人数: ").append(stats.get("totalPlayers")).append("\n");
        sb.append("人均灵石: ").append(formatNum(stats.get("avgStonesPerPlayer"))).append("\n");
        sb.append("24h 交易量: ").append(formatNum(stats.get("last24hTradeVolume"))).append(" 笔");

        ctx.reply(sb.toString());
    }

    private String formatNum(Object obj) {
        if (obj == null) return "0";
        long n;
        if (obj instanceof Number num) n = num.longValue();
        else return obj.toString();
        if (n >= 1_000_000_000) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
