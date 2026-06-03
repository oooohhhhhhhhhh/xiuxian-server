package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.db.DatabaseManager;
import java.util.Map;

public class ClearPlayersDbCommand extends Command {
    public ClearPlayersDbCommand() {
        super(new String[]{"cleardb_players", "清除玩家数据"}, "清除所有玩家数据（仅私聊）", "/cleardb_players", "管理", "admin.database.clear_players", true);
    }
    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.requirePermission("admin.database.clear_players")) return;
        try {
            Map<String, Integer> counts = DatabaseManager.clearPlayerData();
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            StringBuilder sb = new StringBuilder();
            sb.append("===== 清除玩家数据 =====\n共删除 ").append(total).append(" 条记录:\n");
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() > 0) sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append(" 条\n");
            }
            sb.append("\n玩家数据已全部清除。");
            ctx.reply(sb.toString());
        } catch (Exception e) { ctx.reply("清除失败: " + e.getMessage()); }
    }
}
