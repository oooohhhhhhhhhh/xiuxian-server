package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;
import com.mtxgdn.i18n.I18n;

public class ShutdownCommand extends Command {
    public ShutdownCommand() {
        super(new String[]{"关闭服务端", "shutdown", "关闭服务器"},
                "关闭游戏服务器（仅私聊，需要 SUPER_ADMIN 权限）",
                "/关闭服务端",
                "管理", "admin.shutdown", true);
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = ctx.resolveBoundBinding();
        if (b == null) {
            ctx.reply(I18n.t("admin.hd852a51e"));
            return;
        }
        String highestRole = PermissionService.getHighestRole(b.getUserId());
        if (!"SUPER_ADMIN".equals(highestRole)) {
            ctx.reply(I18n.t("admin.hea8fd688"));
            return;
        }
        ctx.reply(I18n.t("admin.h8cce6bb4"));
        Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
        });
        shutdownThread.setDaemon(true);
        shutdownThread.start();
    }
}
