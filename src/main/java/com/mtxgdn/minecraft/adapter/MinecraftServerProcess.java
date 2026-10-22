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

public class MinecraftServerProcess implements Runnable {

    private static final GameLogger log = GameLogger.getLogger(MinecraftServerProcess.class);

    private static final Pattern CHAT_PATTERN =
            Pattern.compile("^\\[.*?INFO\\]:\\s*<(?<name>\\S+)>\\s+(?<msg>.+)$");
    private static final Pattern CHAT_PATTERN_LEGACY =
            Pattern.compile("^<(?<name>\\S+)>\\s+(?<msg>.+)$");
    private static final Pattern JOIN_PATTERN =
            Pattern.compile("(?<name>\\S+)\\s+joined the game");
    private static final Pattern LEAVE_PATTERN =
            Pattern.compile("(?<name>\\S+)\\s+left the game");
    private static final Pattern SERVER_READY_PATTERN =
            Pattern.compile("Done\\s*\\([\\d.]+s\\)!|For help.*type.*help");

    private final String jarPath;
    private final String serverDir;
    private final String javaPath;
    private final int minMemoryMb;
    private final int maxMemoryMb;
    private final List<String> extraJvmArgs;
    private final long readyTimeoutMs;
    private final String commandPrefix;

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
        this.readyTimeoutMs = AppConfig.getInt("minecraft.ready_timeout_seconds", 120) * 1000L;
        this.commandPrefix = AppConfig.get("minecraft.command_prefix", "").trim();

        String extraArgsRaw = AppConfig.get("minecraft.extra_jvm_args", "");
        this.extraJvmArgs = parseExtraArgs(extraArgsRaw);
    }

    private List<String> parseExtraArgs(String extraArgsRaw) {
        List<String> args = new ArrayList<>();
        if (!extraArgsRaw.isBlank()) {
            for (String arg : extraArgsRaw.split("\\s+")) {
                if (!arg.isBlank()) {
                    args.add(arg.trim());
                }
            }
        }
        return args;
    }

    public void setAdapter(MinecraftAdapter adapter) {
        this.adapter = adapter;
    }

    public boolean isRunning() {
        return running.get();
    }

    public String getCommandPrefix() {
        return commandPrefix;
    }

    private void copyAgentJar(Path workDir) {
        try {
            Path targetJar = workDir.resolve("xiuxian-agent.jar");
            Path agentClassDir = Paths.get("target", "classes", "com", "mtxgdn", "minecraft", "agent");
            if (!Files.isDirectory(agentClassDir)) {
                Path classesDir = Paths.get("target", "classes");
                if (Files.isDirectory(classesDir)) {
                    try {
                        createAgentJar(targetJar, classesDir);
                        return;
                    } catch (Exception e) {
                        log.warn("打包 Agent jar 失败: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("复制 Agent jar 失败: " + e.getMessage());
        }
    }

    private void createAgentJar(Path targetJar, Path classesDir) throws IOException, InterruptedException {
        Path metaInfDir = Files.createDirectories(Paths.get("target", "agent-meta", "META-INF"));
        String manifestContent = "Manifest-Version: 1.0\n" +
                "Premain-Class: com.mtxgdn.minecraft.agent.XiuxianAgent\n" +
                "Can-Redefine-Classes: true\n" +
                "Can-Retransform-Classes: true\n";
        Files.write(metaInfDir.resolve("MANIFEST.MF"), manifestContent.getBytes(StandardCharsets.UTF_8));

        ProcessBuilder pb = new ProcessBuilder(
                "jar", "cfm", targetJar.toAbsolutePath().toString(),
                metaInfDir.resolve("MANIFEST.MF").toAbsolutePath().toString(),
                "-C", classesDir.toAbsolutePath().toString(),
                "com/mtxgdn/minecraft/agent");
        pb.inheritIO();
        int exit = pb.start().waitFor();
        if (exit == 0) {
            log.info("已生成 Agent jar: " + targetJar);
        }
    }

    private void copyPluginJar(Path workDir) {
        try {
            Path pluginsDir = workDir.resolve("plugins");
            if (!Files.exists(pluginsDir)) {
                Files.createDirectories(pluginsDir);
            }
            Path targetJar = pluginsDir.resolve("XiuxianBridge.jar");

            Path sourceDir = Paths.get("target", "mc-plugin-classes");
            if (Files.isDirectory(sourceDir)) {
                try {
                    createPluginJar(targetJar, sourceDir);
                    return;
                } catch (Exception e) {
                    log.warn("打包桥接插件失败: " + e.getMessage());
                }
            }

            java.net.URL url = getClass().getClassLoader().getResource("XiuxianBridge.jar");
            if (url != null) {
                try (java.io.InputStream in = url.openStream()) {
                    Files.copy(in, targetJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    log.info("已复制桥接插件: " + targetJar);
                    return;
                }
            }

            log.warn("未找到桥接插件 XiuxianBridge.jar。");
            log.warn("请运行 build-mc-plugin.bat 编译插件，或手动将插件放入 " + pluginsDir);
        } catch (Exception e) {
            log.warn("复制桥接插件失败: " + e.getMessage());
        }
    }

    private void createPluginJar(Path targetJar, Path sourceDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "jar", "cf", targetJar.toAbsolutePath().toString(),
                "-C", sourceDir.toAbsolutePath().toString(), ".",
                "-C", Paths.get("src/main/resources").toAbsolutePath().toString(), "plugin.yml");
        pb.inheritIO();
        Process p = pb.start();
        int exit = p.waitFor();
        if (exit == 0) {
            log.info("已生成桥接插件: " + targetJar);
        }
    }

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

        Path workDir = ensureServerDir();
        if (workDir == null) return false;

        copyAgentJar(workDir);
        copyPluginJar(workDir);

        ProcessBuilder pb = buildProcessBuilder(workDir);
        return startProcess(pb, workDir);
    }

    private Path ensureServerDir() {
        Path workDir = Paths.get(serverDir);
        if (!Files.exists(workDir)) {
            try {
                Files.createDirectories(workDir);
            } catch (IOException e) {
                log.error("无法创建 MC 服务端目录: " + serverDir, e);
                return null;
            }
        }
        return workDir;
    }

    private ProcessBuilder buildProcessBuilder(Path workDir) {
        ProcessBuilder pb = new ProcessBuilder();
        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath);
        cmd.add("-Xms" + minMemoryMb + "M");
        cmd.add("-Xmx" + maxMemoryMb + "M");
        cmd.addAll(extraJvmArgs);

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
        cmd.add("nogui");
        cmd.add("-Dfile.encoding=UTF-8");

        pb.command(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        return pb;
    }

    private boolean startProcess(ProcessBuilder pb, Path workDir) {
        try {
            process = pb.start();
            stdinWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            running.set(true);

            Thread readerThread = new Thread(this, "MC-StdoutReader");
            readerThread.setDaemon(true);
            readerThread.start();

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

    private boolean waitForReady() {
        long deadline = System.currentTimeMillis() + readyTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!running.get()) return false;
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
        String clean = stripAnsi(line);

        if (checkServerReady(clean)) return;
        if (checkPlayerJoin(clean)) return;
        if (checkPlayerLeave(clean)) return;
        checkChatMessage(clean);
    }

    private boolean checkServerReady(String line) {
        if (SERVER_READY_PATTERN.matcher(line).find()) {
            if (adapter != null) {
                adapter.onServerReady();
            }
            return true;
        }
        return false;
    }

    private boolean checkPlayerJoin(String line) {
        Matcher matcher = JOIN_PATTERN.matcher(line);
        if (matcher.find()) {
            String name = matcher.group("name");
            log.info("[MC] 玩家加入: " + name);
            if (adapter != null) {
                adapter.onPlayerJoin(name);
            }
            return true;
        }
        return false;
    }

    private boolean checkPlayerLeave(String line) {
        Matcher matcher = LEAVE_PATTERN.matcher(line);
        if (matcher.find()) {
            String name = matcher.group("name");
            log.info("[MC] 玩家离开: " + name);
            if (adapter != null) {
                adapter.onPlayerLeave(name);
            }
            return true;
        }
        return false;
    }

    private void checkChatMessage(String line) {
        Matcher matcher = CHAT_PATTERN.matcher(line);
        if (!matcher.find()) {
            matcher = CHAT_PATTERN_LEGACY.matcher(line);
        }
        if (matcher.find()) {
            String name = matcher.group("name");
            String msg = matcher.group("msg").trim();
            log.info("[MC 聊天] " + name + ": " + com.mtxgdn.util.LogMasker.mask(msg));
            if (adapter != null) {
                adapter.onChatMessage(name, msg);
            }
        }
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*[ -/]*[@-~]", "");
    }
}