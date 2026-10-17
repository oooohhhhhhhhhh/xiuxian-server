package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.Main;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.util.GameLogger;

/**
 * QQ 私聊重启指令。
 *
 * <p>重启逻辑统一交给 {@link Main#restartServer()}：它会按当前运行环境选择
 * 「自我 spawn」还是「退出等托管方拉起」（systemd / 容器），并保留 JVM 参数。
 * 这里不再自己拼命令行 —— 旧实现固定 sleep 1 秒后就关端口，子进程若更快走到
 * bind 会撞车，且丢掉了 {@code -Xmx} 等 JVM 参数。</p>
 */
public class RestartCommand extends Command {

    private static final GameLogger LOG = GameLogger.getLogger(RestartCommand.class);

    public RestartCommand() {
        super(new String[]{"重启", "restart"},
                "重启游戏服务器（仅私聊，需要 SUPER_ADMIN 权限）",
                "/重启",
                "管理", "admin.shutdown", true);
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = ctx.resolveBoundBinding();
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
        Thread restartThread = new Thread(() -> {
            try {
                // 留出时间把上面的回复发出去，再走统一的重启流程
                Thread.sleep(800);
                Main.restartServer();
            } catch (Exception e) {
                LOG.error("重启失败", e);
                System.exit(0);
            }
        }, "restart-command");
        restartThread.setDaemon(true);
        restartThread.start();
    }
}
