package com.mtxgdn.game.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.identity.QqBinding;
import com.mtxgdn.identity.QqBindingService;
import com.mtxgdn.audit.AuditService;

import java.util.List;
import java.util.Map;
import com.mtxgdn.i18n.I18n;

public class TraceCommand extends Command {

    private static final java.time.format.DateTimeFormatter TRACE_TIME =
            java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                    .withZone(java.time.ZoneId.systemDefault());
    public TraceCommand() {
        super(new String[]{"轨迹", "trace", "玩家轨迹"},
                "查看玩家操作轨迹",
                "/轨迹 [玩家名/QQ/UID]",
                "管理", "qq.command.trace", false);
    
        setI18nKeys("cmd.trace.desc", "cmd.trace.usage");
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = ctx.resolveBoundBinding();
        if (b == null) {
            ctx.reply(I18n.t("admin.please_bind_your_account_first"));
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
                ctx.reply(I18n.t("admin.insufficient_permission"));
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
                    ctx.reply(I18n.t("admin.insufficient_permission_to_view"));
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

        // 审计日志查询：actor=PLAYER；按 UID 精确查，按玩家名/QQ 走 keyword
        List<Map<String, Object>> logs;
        if (targetUserId != null) {
            logs = AuditService.getInstance().query("PLAYER", null, String.valueOf(targetUserId),
                    null, null, null, null, null, limit, 0);
        } else {
            logs = AuditService.getInstance().query("PLAYER", null, null, null, null,
                    targetPlayerName != null ? targetPlayerName : targetQq,
                    null, null, limit, 0);
        }

        if (logs.isEmpty()) {
            ctx.reply(I18n.t("admin.no_activity_trail_records_yet"));
            return;
        }

        StringBuilder sb = new StringBuilder();
        if (isAdmin && targetUserId == null && !isSuperAdmin) {
            sb.append("===== ").append(isAdmin ? "玩家" : "我的").append("操作轨迹 =====\n");
        } else {
            sb.append("===== 操作轨迹 =====\n");
        }

        for (Map<String, Object> log : logs) {
            Object ts = log.get("ts");
            String time = ts instanceof Number n
                    ? TRACE_TIME.format(java.time.Instant.ofEpochMilli(n.longValue()))
                    : "?";
            String pName = (String) log.get("actor_name");
            String action = (String) log.get("action");
            String detail = (String) log.get("detail");

            sb.append("[").append(time).append("] ");
            if (isAdmin && (targetUserId == null || targetUserId != myUserId)) {
                sb.append("【").append(pName != null ? pName : "?").append("】");
            }
            sb.append(action).append(": ").append(detail).append("\n");
        }

        ctx.reply(sb.toString());
    }
}
