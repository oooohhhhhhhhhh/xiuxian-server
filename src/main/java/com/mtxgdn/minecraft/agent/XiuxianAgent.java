package com.mtxgdn.minecraft.agent;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Java Agent —— 在 MC 启动时注入，反射注册 /xiuxian 到 Brigadier CommandDispatcher。
 * <p>
 * 零插件依赖：通过 {@code -javaagent:xiuxian-agent.jar} 在 MC JVM 参数中指定。
 * /xiuxian 与 /gamemode、/time 同级，是原生命令树的一部分。
 */
public class XiuxianAgent {

    private static final String API_URL = "http://localhost:8080/api/mc-command";

    public static void premain(String agentArgs, Instrumentation inst) {
        log("Agent 已加载，等待 MC 服务端就绪...");
        Thread t = new Thread(XiuxianAgent::waitAndInject, "XiuxianAgent");
        t.setDaemon(true);
        t.start();
    }

    private static void waitAndInject() {
        for (int i = 0; i < 80; i++) {
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            try {
                if (injectCommand()) {
                    log("注入成功！/xiuxian 命令已就绪。");
                    return;
                }
            } catch (Exception e) {
                if (i % 5 == 0) log("第" + (i + 1) + "次尝试: " + e.getMessage());
            }
        }
        log("超时，注入失败。");
    }

    private static boolean injectCommand() throws Exception {
        // 1. 找到 MinecraftServer 实例
        Class<?> serverClass = findClass(
                "net.minecraft.server.MinecraftServer",
                "net.minecraft.server.v1_16_R3.MinecraftServer",
                "net.minecraft.server.v1_17_R1.MinecraftServer"
        );
        if (serverClass == null) return false;

        Object server = findServerInstance(serverClass);
        if (server == null) return false;

        // 2. 获取 Commands 和 CommandDispatcher
        Method getCommands = findMethodDeep(serverClass, "getCommands");
        Object commands = getCommands.invoke(server);
        Class<?> commandsClass = commands.getClass();
        Method getDispatcher = findMethodDeep(commandsClass, "getDispatcher");
        Object dispatcher = getDispatcher.invoke(commands);
        log("获取到 Dispatcher: " + dispatcher.getClass().getName());

        // 3. 用动态代理实现 Brigadier Command 接口
        Class<?> brigadierCmd = findClass("com.mojang.brigadier.Command");
        if (brigadierCmd == null) return false;

        Class<?> cmdCtxClass = findClass("com.mojang.brigadier.context.CommandContext");
        ClassLoader cl = dispatcher.getClass().getClassLoader();

        Object handler = Proxy.newProxyInstance(cl, new Class[]{brigadierCmd},
                (proxy, method, args) -> {
                    if (!"run".equals(method.getName())) return null;
                    try {
                        return handleCommand(args[0], cmdCtxClass);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return 0;
                    }
                });

        // 4. 构建 LiteralArgumentBuilder.literal("xiuxian").executes(handler)
        Class<?> labClass = findClass("com.mojang.brigadier.builder.LiteralArgumentBuilder");
        Object literalBuilder = labClass.getMethod("literal", String.class).invoke(null, "xiuxian");
        // .executes(Command)
        literalBuilder = labClass.getMethod("executes", brigadierCmd).invoke(literalBuilder, handler);
        // .build()
        labClass.getMethod("build").invoke(literalBuilder);

        // 5. 注册到 dispatcher
        Class<?> dispatcherClass = dispatcher.getClass();
        Method register = dispatcherClass.getMethod("register",
                findClass("com.mojang.brigadier.builder.LiteralArgumentBuilder"));
        register.invoke(dispatcher, literalBuilder);

        log("命令节点已注册。");
        return true;
    }

    /** 执行命令，转发 HTTP 请求并发送反馈 */
    private static int handleCommand(Object ctx, Class<?> cmdCtxClass) throws Exception {
        // context.getSource() → CommandSourceStack
        Method getSource = cmdCtxClass.getMethod("getSource");
        Object source = getSource.invoke(ctx);

        // source.getTextName() 或 source.getEntity()
        String playerName = "Server";
        String playerUuid = "";
        try {
            Method getName = findMethodDeep(source.getClass(), "getTextName");
            if (getName == null) getName = findMethodDeep(source.getClass(), "getName");
            playerName = (String) getName.invoke(source);

            Method getEntity = findMethodDeep(source.getClass(), "getEntity");
            if (getEntity != null) {
                Object entity = getEntity.invoke(source);
                if (entity != null) {
                    Method getUuid = findMethodDeep(entity.getClass(), "getUUID", "getUniqueID");
                    if (getUuid != null) {
                        playerUuid = getUuid.invoke(entity).toString();
                    }
                }
            }
        } catch (Exception e) {
            // fallback
        }

        // context.getInput() → 完整输入 "/xiuxian status"
        Method getInput = cmdCtxClass.getMethod("getInput");
        String fullInput = (String) getInput.invoke(ctx);

        // 解析子命令和参数
        String[] parts = fullInput.split("\\s+", 3);
        String subCmd = parts.length > 1 ? parts[1] : "";
        String args = parts.length > 2 ? parts[2] : "";

        // HTTP POST 到主服务端
        String result = postCommand(playerName, playerUuid, subCmd, args);

        // 发送反馈
        try {
            Method sendMsg = findMethodDeep(source.getClass(),
                    "sendSystemMessage", "sendSuccess", "sendFailure");
            if (sendMsg == null) return 0;

            // 构建 Component
            Class<?> componentClass = findClass(
                    "net.minecraft.network.chat.Component",
                    "net.minecraft.network.chat.IChatBaseComponent"
            );
            if (componentClass != null) {
                Object component;
                try {
                    // Component.literal()
                    Method literal = findMethod(componentClass, "literal", "nullToEmpty");
                    if (literal != null) {
                        component = literal.invoke(null, result);
                    } else {
                        // 1.16 style: ChatComponentText
                        Class<?> cctClass = findClass("net.minecraft.network.chat.TextComponent",
                                "net.minecraft.network.chat.ChatComponentText");
                        if (cctClass != null) {
                            component = cctClass.getConstructor(String.class).newInstance(result);
                        } else {
                            return 0;
                        }
                    }
                } catch (Exception e) {
                    return 0;
                }
                sendMsg.invoke(source, component);
            }
        } catch (Exception ignored) {}

        return 1; // SUCCESS
    }

    private static String postCommand(String playerName, String playerUuid, String cmd, String args) {
        try {
            String json = "{\"mcName\":\"" + esc(playerName) +
                    "\",\"mcUuid\":\"" + esc(playerUuid) +
                    "\",\"command\":\"" + esc(cmd) +
                    "\",\"args\":\"" + esc(args) + "\"}";

            HttpURLConnection conn = (HttpURLConnection) URI.create(API_URL).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() != 200) return "§c[修仙] 服务未响应";

            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return parseResponse(sb.toString());
            }
        } catch (Exception e) {
            return "§c[修仙] 通信异常: " + e.getMessage();
        }
    }

    /** 简单解析 JSON response */
    private static String parseResponse(String json) {
        if (json.contains("\"ok\":true")) {
            int start = json.indexOf("\"response\":\"");
            if (start < 0) return "§f" + json;
            start += 12;
            int end = json.indexOf("\"", start);
            if (end < 0) end = json.length();
            return "§6[修仙] §r" + unescape(json.substring(start, end));
        }
        int start = json.indexOf("\"error\":\"");
        if (start >= 0) {
            start += 9;
            int end = json.indexOf("\"", start);
            return "§c" + unescape(json.substring(start, end));
        }
        return "§f" + json;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\\", "\\").replace("\\\"", "\"")
                .replace("\\n", "\n").replace("\\r", "\r");
    }

    // ==================== 反射工具 ====================

    private static Class<?> findClass(String... names) {
        for (String name : names) {
            try { return Class.forName(name, false, ClassLoader.getSystemClassLoader()); } catch (Exception ignored) {}
            try { return Class.forName(name); } catch (Exception ignored) {}
        }
        return null;
    }

    private static Object findServerInstance(Class<?> serverClass) {
        // 尝试静态字段
        for (Field f : serverClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && serverClass.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                try { return f.get(null); } catch (Exception ignored) {}
            }
        }
        // 尝试线程堆栈
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (serverClass.isInstance(t)) return t;
        }
        return null;
    }

    private static Method findMethodDeep(Class<?> clazz, String... names) {
        while (clazz != null && clazz != Object.class) {
            for (String name : names) {
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals(name)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                Method m = clazz.getMethod(name);
                if (m != null) return m;
            } catch (Exception ignored) {}
            try {
                Method m = clazz.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void log(String msg) {
        System.out.println("[修仙Agent] " + msg);
    }
}
