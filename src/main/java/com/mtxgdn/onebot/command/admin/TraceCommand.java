package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;

import java.util.List;
import java.util.Map;

public class TraceCommand extends Command {
    public TraceCommand() {
        super(new String[]{"轨迹", "trace", "玩家轨迹"},
                "查看玩家操作轨迹",
                "/轨迹 [玩家名/QQ/UID]",
                "管理", "qq.command.trace", false);
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = new QqBindingService().findByQq(ctx.getSenderId());
        if (b == null) {
            ctx.reply("请先绑定账号。\n私聊使用 /bind");
            return;
        }

        long myUserId = b.getUserId();
        String myHighestRole = PermissionService.getHighestRole(myUserId);
        boolean isSuperAdmin = "SUPER_ADMIN".equals(myHighestRole);
        boolean isAdmin = "ADMIN".equals(myHighestRole) || isSuperAdmin;

        // 确定要查询的玩家
        String arg = ctx.getArg();
        Long targetUserId = null;
        String targetPlayerName = null;
        String targetQq = null;

        if (arg == null || arg.isBlank()) {
            // 无参数：查看自己的轨迹
            targetUserId = myUserId;
        } else {
            // 有参数：按玩家名/QQ/UID查询
            String input = arg.trim();
            if (!isAdmin) {
                // player组只能查看自己的轨迹
                ctx.reply("权限不足，你只能查看自己的轨迹。\n使用 /轨迹 查看自己的操作记录。");
                return;
            }

            // 尝试解析为UID（纯数字）
            try {
                targetUserId = Long.parseLong(input);
            } catch (NumberFormatException e) {
                // 尝试通过QQ查找
                QqBinding targetBinding = new QqBindingService().findByQq(input);
                if (targetBinding != null) {
                    targetUserId = targetBinding.getUserId();
                    targetQq = input;
                } else {
                    // 作为玩家名模糊搜索
                    targetPlayerName = input;
                }
            }

            // 如果指定了targetUserId，检查权限
            if (targetUserId != null) {
                String targetRole = PermissionService.getHighestRole(targetUserId);
                if (!isSuperAdmin && ("ADMIN".equals(targetRole) || "SUPER_ADMIN".equals(targetRole))) {
                    ctx.reply("权限不足，无法查看该用户的操作轨迹。");
                    return;
                }
            }
        }

        // 查询轨迹
        int limit = 10;
        if (ctx.getArg() != null) {
            String[] parts = ctx.getArg().trim().split("\\s+");
            for (String part : parts) {
                try {
                    int n = Integer.parseInt(part);
                    if (n > 0 && n <= 50) {
                        limit = n;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        String qqFilter = targetQq != null ? targetQq : (isAdmin ? null : null);

        List<Map<String, Object>> logs;
        if (isAdmin && targetPlayerName != null) {
            logs = DatabaseManager.queryPlayerActionLogs(null, targetPlayerName, null, null, null, null, limit, 0);
        } else {
            logs = DatabaseManager.queryPlayerActionLogs(targetUserId, null, null, qqFilter, null, null, limit, 0);
        }

        if (logs.isEmpty()) {
            ctx.reply("暂无操作轨迹记录。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        if (isAdmin && targetUserId == null && !isSuperAdmin) {
            sb.append("===== ").append(isAdmin ? "玩家" : "我的").append("操作轨迹 =====\n");
        } else {
            sb.append("===== 操作轨迹 =====\n");
        }

        for (Map<String, Object> log : logs) {
            String time = ((String) log.get("createdAt")).substring(5, 16); // MM-dd HH:mm
            String pName = (String) log.get("playerName");
            String action = (String) log.get("action");
            String detail = (String) log.get("detail");
            String qq = (String) log.get("qqNumber");

            sb.append("[").append(time).append("] ");
            if (isAdmin && (targetUserId == null || targetUserId != myUserId)) {
                sb.append("【").append(pName != null ? pName : "?").append("】");
                if (qq != null && !qq.isEmpty()) {
                    sb.append("(QQ:").append(qq).append(") ");
                }
            }
            sb.append(action).append(": ").append(detail).append("\n");
        }

        ctx.reply(sb.toString());
    }
}
