package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.Main;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;

public class RestartCommand extends Command {
    public RestartCommand() {
        super(new String[]{"重启", "restart"},
                "重启游戏服务器（仅私聊，需要 SUPER_ADMIN 权限）",
                "/重启",
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
        ctx.reply("服务器正在重启...");
        new Thread(() -> {
            try {
                Thread.sleep(500);
                Main.mainServer.shutdownNow();
                if (Main.oneBotServer != null) Main.oneBotServer.shutdownNow();
                Thread.sleep(500);
                System.exit(0);
            } catch (Exception ignored) {
                System.exit(0);
            }
        }).start();
    }
}
