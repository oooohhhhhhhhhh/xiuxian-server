package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.db.DatabaseManager;
import java.util.Map;

public class ResetAllDbCommand extends Command {
    public ResetAllDbCommand() {
        super(new String[]{"cleardb_all", "重置全部数据"}, "重置全部数据并重新初始化（仅私聊）", "/cleardb_all", "管理", "admin.database.reset_all", true);
    }
    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.requirePermission("admin.database.reset_all")) return;
        try {
            Map<String, Integer> counts = DatabaseManager.resetAllData();
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            StringBuilder sb = new StringBuilder();
            sb.append("===== 重置全部数据 =====\n共删除 ").append(total).append(" 条记录:\n");
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() > 0) sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append(" 条\n");
            }
            sb.append("\n全部数据已重置，默认角色权限已重新初始化。");
            ctx.reply(sb.toString());
        } catch (Exception e) { ctx.reply("重置失败: " + e.getMessage()); }
    }
}
