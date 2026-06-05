package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.Main;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;

public class ShutdownCommand extends Command {
    public ShutdownCommand() {
        super(new String[]{"关闭服务端", "shutdown", "关闭服务器"},
                "关闭游戏服务器（仅私聊，需要 SUPER_ADMIN 权限）",
                "/关闭服务端",
                "管理", "admin.shutdown", true);
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = new QqBindingService().findByQq(ctx.getSenderId());
        if (b == null) {
            ctx.reply("请先绑定账号。");
            return;
        }
        String highestRole = PermissionService.getHighestRole(b.getUserId());
        if (!"SUPER_ADMIN".equals(highestRole)) {
            ctx.reply("权限不足，仅 SUPER_ADMIN 可以执行此操作。");
            return;
        }
        ctx.reply("服务器正在关闭...");
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
        }).start();
    }
}
