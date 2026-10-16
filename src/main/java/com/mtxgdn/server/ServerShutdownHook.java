package com.mtxgdn.server;

import com.mtxgdn.game.service.BuffService;
import com.mtxgdn.game.service.CaveService;
import com.mtxgdn.game.service.FarmService;
import com.mtxgdn.game.service.FormationService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.plugin.PluginManager;
import com.mtxgdn.util.GameLogger;

import java.util.ArrayList;
import java.util.List;

public class ServerShutdownHook {

    private static final GameLogger LOG = GameLogger.getLogger(ServerShutdownHook.class);

    private final List<ShutdownTask> tasks = new ArrayList<>();

    public void addTask(Runnable task) {
        tasks.add(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOG.warn("关闭任务执行异常: " + e.getMessage());
            }
        });
    }

    public void register() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::execute, "shutdown-hook"));
    }

    private void execute() {
        LOG.info("服务器正在优雅关闭...");

        for (ShutdownTask task : tasks) {
            try {
                task.shutdown();
            } catch (Exception e) {
                LOG.warn("关闭任务执行失败: " + e.getMessage());
            }
        }

        LOG.info("服务器已关闭");
    }

    public static ServerShutdownHook createDefault() {
        ServerShutdownHook hook = new ServerShutdownHook();

        hook.addTask(() -> PluginManager.getInstance().disablePlugins());
        hook.addTask(() -> PluginManager.getInstance().stopFileWatcher());
        hook.addTask(PlayerService::shutdown);
        hook.addTask(FormationService::shutdown);
        hook.addTask(FarmService::shutdownScheduler);
        hook.addTask(CaveService::shutdown);
        hook.addTask(BuffService::shutdownScheduler);

        return hook;
    }

    @FunctionalInterface
    public interface ShutdownTask {
        void shutdown() throws Exception;
    }
}