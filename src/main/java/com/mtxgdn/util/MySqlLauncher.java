package com.mtxgdn.util;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MySqlLauncher {

    private static final GameLogger LOG = GameLogger.getLogger(MySqlLauncher.class);

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 3306;
    private static final int MAX_WAIT_SECONDS = 30;
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {1000, 3000, 6000};

    private static Process mysqlProcess;
    private static boolean shutdownHookRegistered;

    public enum StartResult {
        ALREADY_RUNNING,
        SUCCESS,
        PATH_NOT_FOUND,
        PROCESS_DIED,
        PORT_IN_USE_BY_OTHER,
        TIMEOUT,
        IO_ERROR
    }

    private static class StartAttempt {
        final StartResult result;
        final String detail;

        StartAttempt(StartResult result, String detail) {
            this.result = result;
            this.detail = detail;
        }
    }

    public static void ensureMySqlRunning() {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;

        if (isPortOpen(host, port)) {
            LOG.info("MySQL 已在运行 (端口 " + port + ")");
            return;
        }

        LOG.info("MySQL 未运行，正在启动...");

        String mysqldPath = findMySqldPath();
        if (mysqldPath == null) {
            LOG.error("找不到 mysqld.exe，无法启动 MySQL");
            LOG.error("已搜索以下位置:");
            reportSearchPaths();
            LOG.error("解决方法:");
            LOG.error("  1. 确保 MySQL 已安装，mysqld.exe 位于上述路径之一");
            LOG.error("  2. 或将 MySQL bin 目录添加到系统 PATH 环境变量");
            return;
        }

        LOG.info("找到 mysqld: " + mysqldPath);

        List<StartAttempt> attempts = new ArrayList<>();
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long delay = RETRY_DELAYS_MS[Math.min(attempt - 1, RETRY_DELAYS_MS.length - 1)];
                LOG.info("第 " + (attempt + 1) + " 次重试，等待 " + (delay / 1000) + " 秒...");
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                killPreviousProcess();
            }

            StartAttempt sa = tryStartMySql(mysqldPath, host, port, attempt + 1);
            attempts.add(sa);

            if (sa.result == StartResult.SUCCESS) {
                LOG.info("MySQL 启动成功！");
                return;
            }

            LOG.error("启动失败: " + sa.detail);
        }

        LOG.error("MySQL 启动失败，已重试 " + attempts.size() + " 次");
        for (int i = 0; i < attempts.size(); i++) {
            StartAttempt sa = attempts.get(i);
            LOG.error("  第" + (i + 1) + "次: [" + sa.result + "] " + sa.detail);
        }
        LOG.error("请检查以下可能原因:");
        LOG.error("  1. MySQL 数据目录是否已初始化 (运行 mysqld --initialize)");
        LOG.error("  2. 端口 " + port + " 是否被其他程序占用");
        LOG.error("  3. 是否有足够权限运行 mysqld.exe");
        LOG.error("  4. my.ini 配置是否正确");
    }

    private static StartAttempt tryStartMySql(String mysqldPath, String host, int port, int attemptNo) {
        try {
            ProcessBuilder pb = new ProcessBuilder(mysqldPath, "--console");
            pb.redirectErrorStream(true);
            mysqlProcess = pb.start();

            registerShutdownHookOnce();

            StringBuilder outputCapture = new StringBuilder();
            consumeStreamAsync(mysqlProcess.getInputStream(), "[MySQL] ", outputCapture);

            LOG.info("等待 MySQL 就绪 (端口 " + port + ")...");
            boolean portReady = waitForPortWithProcessCheck(host, port, MAX_WAIT_SECONDS, outputCapture);

            if (portReady) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                }
                return new StartAttempt(StartResult.SUCCESS, "端口 " + port + " 已就绪");
            }

            if (!mysqlProcess.isAlive()) {
                int exitCode = mysqlProcess.exitValue();
                String tail = tailOutput(outputCapture, 500);
                return new StartAttempt(StartResult.PROCESS_DIED,
                        "mysqld 进程意外退出，退出码=" + exitCode
                                + (tail.isEmpty() ? "" : "，最近输出:\n[MySQL] " + tail));
            }

            if (isPortOpen(host, port)) {
                return new StartAttempt(StartResult.PORT_IN_USE_BY_OTHER,
                        "端口 " + port + " 已被其他进程占用");
            }

            return new StartAttempt(StartResult.TIMEOUT,
                    "等待 " + MAX_WAIT_SECONDS + " 秒后端口仍未就绪，进程仍在运行但未监听端口");

        } catch (IOException e) {
            return new StartAttempt(StartResult.IO_ERROR,
                    "IO异常: " + e.getMessage() + " (类型: " + e.getClass().getSimpleName() + ")");
        }
    }

    private static String findMySqldPath() {
        List<String> searchPaths = new ArrayList<>();
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");

        if (programFiles != null) {
            searchPaths.add(programFiles + "\\MySQL");
        }
        if (programFilesX86 != null) {
            searchPaths.add(programFilesX86 + "\\MySQL");
        }
        searchPaths.add("C:\\xampp\\mysql\\bin");
        searchPaths.add("C:\\wamp64\\bin\\mysql");
        searchPaths.add("C:\\wamp\\bin\\mysql");
        searchPaths.add("D:\\xampp\\mysql\\bin");
        searchPaths.add("D:\\wamp64\\bin\\mysql");

        for (String basePath : searchPaths) {
            Path base = Paths.get(basePath);
            if (!Files.isDirectory(base)) {
                continue;
            }
            Path directExe = base.resolve("bin\\mysqld.exe");
            if (Files.isExecutable(directExe)) {
                return directExe.toAbsolutePath().toString();
            }
            try {
                String found = Files.list(base)
                        .filter(Files::isDirectory)
                        .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                        .map(dir -> dir.resolve("bin\\mysqld.exe"))
                        .filter(Files::isExecutable)
                        .findFirst()
                        .map(Path::toAbsolutePath)
                        .map(Path::toString)
                        .orElse(null);
                if (found != null) {
                    return found;
                }
            } catch (IOException ignored) {
            }
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                Path exePath = Paths.get(dir, "mysqld.exe");
                if (Files.isExecutable(exePath)) {
                    return exePath.toAbsolutePath().toString();
                }
            }
        }

        return null;
    }

    private static void reportSearchPaths() {
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        if (programFiles != null) {
            LOG.error("  - " + programFiles + "\\MySQL\\<版本>\\bin\\mysqld.exe");
        }
        if (programFilesX86 != null) {
            LOG.error("  - " + programFilesX86 + "\\MySQL\\<版本>\\bin\\mysqld.exe");
        }
        LOG.error("  - C:\\xampp\\mysql\\bin\\mysqld.exe");
        LOG.error("  - D:\\xampp\\mysql\\bin\\mysqld.exe");
        LOG.error("  - C:\\wamp64\\bin\\mysql\\<版本>\\bin\\mysqld.exe");
        LOG.error("  - PATH 环境变量中的 mysqld.exe");
    }

    private static boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean waitForPortWithProcessCheck(String host, int port, int maxWaitSeconds,
                                                        StringBuilder outputCapture) {
        int waited = 0;
        while (waited < maxWaitSeconds) {
            if (isPortOpen(host, port)) {
                return true;
            }
            if (!mysqlProcess.isAlive()) {
                return false;
            }
            try {
                Thread.sleep(1000);
                waited++;
                if (waited % 5 == 0) {
                    LOG.info("等待中... (" + waited + "s/" + maxWaitSeconds + "s)");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isPortOpen(host, port);
    }

    private static String tailOutput(StringBuilder sb, int maxChars) {
        if (sb.length() == 0) {
            return "";
        }
        int start = Math.max(0, sb.length() - maxChars);
        String tail = sb.substring(start);
        return tail.replace("\n", "\n[MySQL] ");
    }

    private static void consumeStreamAsync(InputStream inputStream, String prefix, StringBuilder capture) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.debug(prefix + line);
                    if (capture != null) {
                        capture.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private static void registerShutdownHookOnce() {
        if (!shutdownHookRegistered) {
            shutdownHookRegistered = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                killPreviousProcess();
            }));
        }
    }

    private static void killPreviousProcess() {
        if (mysqlProcess != null && mysqlProcess.isAlive()) {
            LOG.info("正在关闭之前的 MySQL 进程...");
            mysqlProcess.destroy();
            try {
                mysqlProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (mysqlProcess.isAlive()) {
                mysqlProcess.destroyForcibly();
            }
        }
    }

    public static void shutdown() {
        killPreviousProcess();
    }
}
