package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.Main;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;
import com.mtxgdn.i18n.I18n;

public class StatusCommand extends Command {
    public StatusCommand() {
        super(new String[]{"服务器状态", "serverstatus"},
                "查看服务器运行状态（仅私聊）",
                "/服务器状态",
                "管理", "admin.status", true);
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = ctx.resolveBoundBinding();
        if (b == null) {
            ctx.reply(I18n.t("admin.hd852a51e"));
            return;
        }
        if (!PermissionService.hasPermission(b.getUserId(), "admin.status")) {
            ctx.reply(I18n.t("admin.ha43d576a"));
            return;
        }

        long uptime = System.currentTimeMillis() - Main.serverStartTime;
        int onlineCount = Main.gameWebSocketApp != null ? Main.gameWebSocketApp.getOnlineCount() : 0;
        Runtime runtime = Runtime.getRuntime();
        long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMB = runtime.maxMemory() / (1024 * 1024);

        long seconds = uptime / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        String uptimeStr;
        if (days > 0) {
            uptimeStr = String.format("%dd %02d:%02d:%02d", days, hours, minutes, secs);
        } else {
            uptimeStr = String.format("%02d:%02d:%02d", hours, minutes, secs);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== 服务器状态 =====\n");
        sb.append("运行时间: ").append(uptimeStr).append("\n");
        sb.append("在线玩家: ").append(onlineCount).append("\n");
        sb.append("内存使用: ").append(usedMB).append(" MB / ").append(maxMB).append(" MB\n");
        sb.append("=====================");
        ctx.reply(sb.toString());
    }
}
