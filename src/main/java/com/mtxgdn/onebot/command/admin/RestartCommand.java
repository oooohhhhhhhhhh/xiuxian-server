package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.Main;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;
import com.mtxgdn.util.GameLogger;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

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
                Thread.sleep(800);

                // 构建重启命令
                List<String> cmd = buildRestartCommand();
                if (cmd == null) {
                    LOG.error("无法构建重启命令，尝试直接退出");
                    System.exit(0);
                    return;
                }

                LOG.info("重启命令: " + String.join(" ", cmd));

                // 启动新进程
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(new File(System.getProperty("user.dir")));
                pb.inheritIO();
                pb.start();

                // 等待新进程启动后关闭当前进程
                Thread.sleep(1000);

                Main.mainServer.shutdownNow();
                com.mtxgdn.adapter.AdapterManager.stopAll();
                Thread.sleep(500);
                System.exit(0);
            } catch (Exception e) {
                LOG.error("重启失败", e);
                System.exit(0);
            }
        });
        restartThread.setDaemon(true);
        restartThread.start();
    }

    private static List<String> buildRestartCommand() {
        try {
            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + File.separator + "bin" + File.separator + "java";

            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);

            // 传入JVM参数（排除调试参数）
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (!arg.startsWith("-agentlib") && !arg.startsWith("-Xrunjdwp")) {
                    cmd.add(arg);
                }
            }

            // 判断是 -jar 启动还是主类启动
            String classPath = ManagementFactory.getRuntimeMXBean().getClassPath();
            String mainCommand = System.getProperty("sun.java.command");

            // mainCommand 格式: "xxx.jar [args]" (jar启动) 或 "com.example.Main [args]" (类启动)
            String firstToken = mainCommand != null ? mainCommand.split("\\s+", 2)[0] : "";

            if (firstToken.endsWith(".jar")) {
                // fat JAR 启动: java -jar xxx.jar [args]
                cmd.add("-jar");
                cmd.add(classPath);
                // 提取 JAR 后面的程序参数
                int jarEnd = mainCommand.indexOf(".jar") + 4;
                if (jarEnd < mainCommand.length()) {
                    String extraArgs = mainCommand.substring(jarEnd).trim();
                    if (!extraArgs.isEmpty()) {
                        for (String a : extraArgs.split("\\s+")) {
                            cmd.add(a);
                        }
                    }
                }
            } else {
                // 主类启动: java -cp <classpath> <mainClass> [args]
                cmd.add("-cp");
                cmd.add(classPath);
                if (mainCommand != null) {
                    String[] parts = mainCommand.split("\\s+", 2);
                    cmd.add(parts[0]); // main class
                }
            }

            return cmd;
        } catch (Exception e) {
            LOG.error("构建重启命令失败", e);
            return null;
        }
    }
}
