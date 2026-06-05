package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.permission.PermissionCode;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;

import java.util.List;
import java.util.Map;

public class UsersRolesCommand extends Command {
    public UsersRolesCommand() {
        super(new String[]{"用户列表", "users", "userlist"},
                "查看所有用户及其角色（仅私聊）",
                "/用户列表",
                "管理", "admin.status", true);
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = new QqBindingService().findByQq(ctx.getSenderId());
        if (b == null) {
            ctx.reply("请先绑定账号。");
            return;
        }
        if (!PermissionService.hasPermission(b.getUserId(), "admin.status")) {
            ctx.reply("权限不足，你无权使用此功能。");
            return;
        }

        List<Map<String, Object>> users = PermissionService.getAllUsersWithRoles();

        StringBuilder sb = new StringBuilder();
        sb.append("===== 用户列表 =====\n");
        sb.append("共 ").append(users.size()).append(" 个用户\n\n");

        for (Map<String, Object> user : users) {
            long id = ((Number) user.get("id")).longValue();
            String username = (String) user.get("username");
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) user.get("roles");

            sb.append("ID:").append(id).append(" ").append(username);
            if (roles != null && !roles.isEmpty()) {
                sb.append(" [").append(String.join(", ", roles)).append("]");
            }
            // also show QQ binding
            QqBinding binding = new QqBindingService().findByUserId(id);
            if (binding != null) {
                sb.append(" QQ:").append(binding.getQqNumber());
            }
            sb.append("\n");
        }

        sb.append("\n===== 角色层级 =====\n");
        for (Map.Entry<String, Integer> entry : PermissionService.getRoleHierarchy().entrySet()) {
            sb.append(entry.getKey()).append(" (Lv.").append(entry.getValue()).append("): ");
            var perms = PermissionService.getRoleDefaultPermissions(entry.getKey());
            List<String> permCodes = perms.stream().map(PermissionCode::getCode).toList();
            sb.append(String.join(", ", permCodes)).append("\n");
        }

        ctx.reply(sb.toString());
    }
}
