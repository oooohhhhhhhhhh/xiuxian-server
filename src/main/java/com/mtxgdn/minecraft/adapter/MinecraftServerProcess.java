package com.mtxgdn.minecraft.adapter;

import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft 服务端进程管理器。
 * <p>
 * 启动 MC 服务端 jar，通过 stdin/stdout 实现通信：
 * <ul>
 *   <li>stdout → 解析聊天消息、玩家上下线事件</li>
 *   <li>stdin  → 写入 tellraw/msg 等指令来发送消息</li>
 * </ul>
 * 通用方案：无需任何插件/Mod，支持原版、Forge、Fabric、Spigot 等所有 Java 版服务端。
 */
public class MinecraftServerProcess implements Runnable {

    private static final GameLogger log = GameLogger.getLogger(MinecraftServerProcess.class);

    /** 聊天消息正则：匹配 "[时间] [线程/INFO]: <玩家名> 消息内容" */
    private static final Pattern CHAT_PATTERN =
            Pattern.compile("^\\[.*?INFO\\]:\\s*<(?<name>\\S+)>\\s+(?<msg>.+)$");
    /** 古代版本（无时间戳）格式：<玩家名> 消息 */
    private static final Pattern CHAT_PATTERN_LEGACY =
            Pattern.compile("^<(?<name>\\S+)>\\s+(?<msg>.+)$");
    private final String commandPrefix; // e.g. "xiuxian" → /xiuxian status, "" → /status
    /** 玩家加入 */
    private static final Pattern JOIN_PATTERN =
            Pattern.compile("(?<name>\\S+)\\s+joined the game");
    /** 玩家离开 */
    private static final Pattern LEAVE_PATTERN =
            Pattern.compile("(?<name>\\S+)\\s+left the game");
    /** 服务端启动完成标志 */
    private static final Pattern SERVER_READY_PATTERN =
            Pattern.compile("Done\\s*\\([\\d.]+s\\)!|For help.*type.*help");

    private final String jarPath;
    private final String serverDir;
    private final String javaPath;
    private final int minMemoryMb;
    private final int maxMemoryMb;
    private final List<String> extraJvmArgs;
    private final long readyTimeoutMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Process process;
    private volatile BufferedWriter stdinWriter;
    private MinecraftAdapter adapter;

    public MinecraftServerProcess() {
        this.jarPath = AppConfig.get("minecraft.jar_path", "");
        this.serverDir = AppConfig.get("minecraft.server_dir", "mc_server");
        this.javaPath = AppConfig.get("minecraft.java_path", "java");
        this.minMemoryMb = AppConfig.getInt("minecraft.min_memory_mb", 512);
        this.maxMemoryMb = AppConfig.getInt("minecraft.max_memory_mb", 1024);
        this.autoStart = AppConfig.getBoolean("minecraft.auto_start", false);
        this.readyTimeoutMs = AppConfig.getInt("minecraft.ready_timeout_seconds", 120) * 1000L;
        this.commandPrefix = AppConfig.get("minecraft.command_prefix", "").trim();

        String extraArgsRaw = AppConfig.get("minecraft.extra_jvm_args", "");
        this.extraJvmArgs = new ArrayList<>();
        if (!extraArgsRaw.isBlank()) {
            for (String arg : extraArgsRaw.split("\\s+")) {
                if (!arg.isBlank()) {
                    extraJvmArgs.add(arg.trim());
                }
            }
        }
    }

    public void setAdapter(MinecraftAdapter adapter) {
        this.adapter = adapter;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 指令前缀，空串表示无前缀 */
    public String getCommandPrefix() {
        return commandPrefix;
    }

    /**
     * 从项目 target/classes 中打包 Agent jar 并复制到 MC 服务端目录。
     */
    private void copyAgentJar(Path workDir) {
        try {
            Path targetJar = workDir.resolve("xiuxian-agent.jar");
            Path agentClassDir = Paths.get("target", "classes", "com", "mtxgdn", "minecraft", "agent");
            if (!Files.isDirectory(agentClassDir)) {
                // 尝试直接从 target/classes 根目录打包
                Path classesDir = Paths.get("target", "classes");
                if (Files.isDirectory(classesDir)) {
                    try {
                        // 创建 MANIFEST.MF
                        Path metaInfDir = Files.createDirectories(
                                Paths.get("target", "agent-meta", "META-INF"));
                        String manifestContent = "Manifest-Version: 1.0\n" +
                                "Premain-Class: com.mtxgdn.minecraft.agent.XiuxianAgent\n" +
                                "Can-Redefine-Classes: true\n" +
                                "Can-Retransform-Classes: true\n";
                        Files.write(metaInfDir.resolve("MANIFEST.MF"),
                                manifestContent.getBytes(StandardCharsets.UTF_8));

                        ProcessBuilder pb = new ProcessBuilder(
                                "jar", "cfm", targetJar.toAbsolutePath().toString(),
                                metaInfDir.resolve("MANIFEST.MF").toAbsolutePath().toString(),
                                "-C", classesDir.toAbsolutePath().toString(),
                                "com/mtxgdn/minecraft/agent");
                        pb.inheritIO();
                        int exit = pb.start().waitFor();
                        if (exit == 0) {
                            log.info("已生成 Agent jar: " + targetJar);
                            return;
                        }
                    } catch (Exception e) {
                        log.warn("打包 Agent jar 失败: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("复制 Agent jar 失败: " + e.getMessage());
        }
    }
    private void copyPluginJar(Path workDir) {
        try {
            Path pluginsDir = workDir.resolve("plugins");
            if (!Files.exists(pluginsDir)) {
                Files.createDirectories(pluginsDir);
            }
            Path targetJar = pluginsDir.resolve("XiuxianBridge.jar");

            // 先尝试从本地构建输出目录复制
            Path sourceDir = Paths.get("target", "mc-plugin-classes");
            if (Files.isDirectory(sourceDir)) {
                // 现场打包 jar
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                            "jar", "cf", targetJar.toAbsolutePath().toString(),
                            "-C", sourceDir.toAbsolutePath().toString(), ".",
                            "-C", Paths.get("src/main/resources").toAbsolutePath().toString(), "plugin.yml");
                    pb.inheritIO();
                    Process p = pb.start();
                    int exit = p.waitFor();
                    if (exit == 0) {
                        log.info("已生成桥接插件: " + targetJar);
                        return;
                    }
                } catch (Exception e) {
                    log.warn("打包桥接插件失败: " + e.getMessage());
                }
            }

            // 回退：从 classpath 查找预打包的 jar
            java.net.URL url = getClass().getClassLoader().getResource("XiuxianBridge.jar");
            if (url != null) {
                try (java.io.InputStream in = url.openStream()) {
                    Files.copy(in, targetJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    log.info("已复制桥接插件: " + targetJar);
                    return;
                }
            }

            // 还没找到 — 给出提示但不阻断启动
            log.warn("未找到桥接插件 XiuxianBridge.jar。");
            log.warn("请运行 build-mc-plugin.bat 编译插件，或手动将插件放入 " + pluginsDir);
        } catch (Exception e) {
            log.warn("复制桥接插件失败: " + e.getMessage());
        }
    }

    /**
     * 启动 MC 服务端（阻塞直到服务端就绪或超时）。
     * @return true 表示启动成功
     */
    public boolean start() {
        if (jarPath == null || jarPath.isBlank()) {
            log.warn("未配置 minecraft.jar_path，跳过 MC 服务端启动。");
            return false;
        }
        Path jar = Paths.get(jarPath);
        if (!Files.exists(jar)) {
            log.error("MC 服务端 jar 不存在: " + jarPath);
            return false;
        }

        Path workDir = Paths.get(serverDir);
        if (!Files.exists(workDir)) {
            try {
                Files.createDirectories(workDir);
            } catch (IOException e) {
                log.error("无法创建 MC 服务端目录: " + serverDir, e);
                return false;
            }
        }

        // 自动复制 Agent jar 到服务端目录，并生成插件（备选）
        copyAgentJar(workDir);
        copyPluginJar(workDir);

        ProcessBuilder pb = new ProcessBuilder();
        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath);
        cmd.add("-Xms" + minMemoryMb + "M");
        cmd.add("-Xmx" + maxMemoryMb + "M");
        cmd.addAll(extraJvmArgs);

        // Java Agent 注入：注册 /xiuxian 为 MC 原生命令
        Path agentJar = workDir.resolve("xiuxian-agent.jar");
        if (Files.exists(agentJar)) {
            cmd.add("-javaagent:" + agentJar.toAbsolutePath());
            log.info("Java Agent: " + agentJar.toAbsolutePath());
        } else {
            log.warn("未找到 xiuxian-agent.jar，命令注入将不可用。");
            log.warn("请运行 build-mc-agent.bat 编译 Agent。");
        }

        cmd.add("-jar");
        cmd.add(jarPath);
        cmd.add("nogui"); // 无GUI模式
        // 确保使用UTF-8编码
        cmd.add("-Dfile.encoding=UTF-8");

        pb.command(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        try {
            process = pb.start();
            stdinWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            running.set(true);

            // 启动 stdout 读取线程
            Thread readerThread = new Thread(this, "MC-StdoutReader");
            readerThread.setDaemon(true);
            readerThread.start();

            // 等待服务端就绪
            log.info("正在启动 Minecraft 服务端 (jar=" + jarPath + ")...");
            boolean ready = waitForReady();
            if (ready) {
                log.info("Minecraft 服务端已就绪！");
            } else {
                log.warn("等待 Minecraft 服务端就绪超时，但进程已启动。");
            }
            return true;
        } catch (IOException e) {
            log.error("启动 Minecraft 服务端失败: " + e.getMessage(), e);
            running.set(false);
            return false;
        }
    }

    /** 等待服务端输出 "Done" 标志 */
    private boolean waitForReady() {
        // ready 状态由 run() 中的 stdout 解析线程设置
        // 这里我们依靠一个 volatile 标志或简单轮询
        long deadline = System.currentTimeMillis() + readyTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!running.get()) return false;
            // 服务端就绪信号由 adapter 在 onServerReady() 中标记
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    public void stop() {
        running.set(false);
        if (process != null && process.isAlive()) {
            try {
                // 发送 /stop 指令优雅关闭
                sendCommand("stop");
                process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("等待MC服务端关闭超时，强制终止");
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 向MC服务端发送控制台指令 */
    public void sendCommand(String command) {
        if (stdinWriter == null || process == null || !process.isAlive()) return;
        try {
            stdinWriter.write(command);
            stdinWriter.newLine();
            stdinWriter.flush();
        } catch (IOException e) {
            log.error("向MC服务端写入指令失败: " + e.getMessage());
        }
    }

    // ==================== stdout 读取线程 ====================

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8"))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                parseLine(line);
            }
        } catch (IOException e) {
            if (running.get()) {
                log.error("读取 MC 服务端 stdout 异常: " + e.getMessage());
            }
        } finally {
            running.set(false);
            if (adapter != null) {
                adapter.onServerStopped();
            }
            log.info("Minecraft 服务端进程已退出");
        }
    }

    private void parseLine(String line) {
        // 去除 ANSI 颜色码
        String clean = stripAnsi(line);

        // 检查服务端就绪
        if (SERVER_READY_PATTERN.matcher(clean).find()) {
            if (adapter != null) {
                adapter.onServerReady();
            }
            return;
        }

        // 检查玩家加入
        Matcher joinMatcher = JOIN_PATTERN.matcher(clean);
        if (joinMatcher.find()) {
            String name = joinMatcher.group("name");
            log.info("[MC] 玩家加入: " + name);
            if (adapter != null) {
                adapter.onPlayerJoin(name);
            }
            return;
        }

        // 检查玩家离开
        Matcher leaveMatcher = LEAVE_PATTERN.matcher(clean);
        if (leaveMatcher.find()) {
            String name = leaveMatcher.group("name");
            log.info("[MC] 玩家离开: " + name);
            if (adapter != null) {
                adapter.onPlayerLeave(name);
            }
            return;
        }

        // 检查聊天消息
        Matcher chatMatcher = CHAT_PATTERN.matcher(clean);
        if (!chatMatcher.find()) {
            chatMatcher = CHAT_PATTERN_LEGACY.matcher(clean);
        }
        if (chatMatcher.find()) {
            String name = chatMatcher.group("name");
            String msg = chatMatcher.group("msg").trim();
            log.info("[MC 聊天] " + name + ": " + msg);
            if (adapter != null) {
                adapter.onChatMessage(name, msg);
            }
        }
    }

    /** 简单去除 ANSI 转义序列 */
    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*[ -/]*[@-~]", "");
    }
}
