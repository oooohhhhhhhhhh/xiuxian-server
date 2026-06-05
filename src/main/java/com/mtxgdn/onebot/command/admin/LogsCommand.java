package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;
import com.mtxgdn.util.GameLogger;

import java.util.ArrayList;
import java.util.List;

public class LogsCommand extends Command {
    public LogsCommand() {
        super(new String[]{"日志", "logs", "服务器日志"},
                "查看服务器最近日志（仅私聊）",
                "/日志 [条数]",
                "管理", "admin.logs.view", true);
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = new QqBindingService().findByQq(ctx.getSenderId());
        if (b == null) {
            ctx.reply("请先绑定账号。");
            return;
        }
        if (!PermissionService.hasPermission(b.getUserId(), "admin.logs.view")) {
            ctx.reply("权限不足，你无权使用此功能。");
            return;
        }

        int count = 10;
        String arg = ctx.getArg();
        if (arg != null && !arg.isBlank()) {
            try {
                count = Math.min(Integer.parseInt(arg.trim()), 50);
            } catch (NumberFormatException ignored) {
            }
        }

        List<GameLogger.LogEntry> allEntries = GameLogger.getRecentLogs(0);
        List<GameLogger.LogEntry> entries;
        if (allEntries.size() <= count) {
            entries = allEntries;
        } else {
            entries = new ArrayList<>(allEntries.subList(allEntries.size() - count, allEntries.size()));
        }

        if (entries.isEmpty()) {
            ctx.reply("暂无日志。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== 最近 ").append(entries.size()).append(" 条日志 =====\n");
        for (GameLogger.LogEntry entry : entries) {
            sb.append("[").append(entry.timestamp).append("] [").append(entry.level).append("] ")
                    .append(entry.message).append("\n");
        }
        ctx.reply(sb.toString());
    }
}
