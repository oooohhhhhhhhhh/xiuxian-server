package com.mtxgdn.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.util.GameLogger;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MinecraftMotdServer {

    private static final GameLogger LOG = GameLogger.getLogger(MinecraftMotdServer.class);

    private final Config config;
    private final ExecutorService threadPool;
    private final AtomicInteger connectionCount;
    private volatile boolean running;
    private ServerSocket serverSocket;

    private static final int SOCKET_TIMEOUT_MS = 500;
    private static final int PING_TIMEOUT_MS = 50;

    /** 透传（TCP 隧道）时连接目标服务器的超时（毫秒）。 */
    private static final int RELAY_CONNECT_TIMEOUT_MS = 8000;
    /** 透传泵缓冲区大小。 */
    private static final int RELAY_BUFFER_SIZE = 8192;
    /** 单包长度上限（防恶意超大长度导致内存耗尽；Minecraft 协议默认上限约 2MB）。 */
    private static final int MAX_PACKET_BYTES = 8 * 1024 * 1024;

    private MinecraftMotdServer(Config config) {
        this.config = config;
        this.threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MC-MOTD-Worker");
            t.setDaemon(true);
            return t;
        });
        this.connectionCount = new AtomicInteger(0);
    }

    public void start() {
        running = true;
        Thread serverThread = new Thread(this::listen, "MC-MOTD");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        threadPool.shutdownNow();
    }

    public int getConnectionCount() {
        return connectionCount.get();
    }

    private void listen() {
        try (ServerSocket ss = new ServerSocket(config.port)) {
            this.serverSocket = ss;
            LOG.info(String.format("[MOTD] Minecraft MOTD 服务已启动在 0.0.0.0:%d", config.port));
            while (running) {
                try {
                    Socket socket = ss.accept();
                    connectionCount.incrementAndGet();
                    threadPool.submit(() -> {
                        try {
                            handleClient(socket);
                        } finally {
                            connectionCount.decrementAndGet();
                        }
                    });
                } catch (IOException e) {
                    if (running) {
                        LOG.error("[MOTD] 接受连接失败", e);
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                LOG.error("[MOTD] 启动失败", e);
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            // 读取并缓存握手包的原始字节：跳转到目标服务器（TCP 透传）时需要原样转发。
            byte[] rawHandshake = readRawPacket(in);
            ParsedHandshake handshake = parseHandshake(rawHandshake);
            if (handshake == null) {
                return;
            }

            if (handshake.packetId == 0xFE) {
                handleLegacyPing(socket, out);
                return;
            }

            if (handshake.packetId != 0x00) {
                return;
            }

            switch (handshake.nextState) {
                case 1:
                    handleStatusSequence(socket, in, out);
                    break;
                case 2:
                    handleLogin(socket, in, out, rawHandshake);
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleStatusSequence(Socket socket, DataInputStream in, DataOutputStream out) throws IOException {
        VarInt.readVarInt(in);
        int packetId = VarInt.readVarInt(in);

        if (packetId != 0x00) {
            return;
        }

        sendStatusResponse(out);

        try {
            socket.setSoTimeout(PING_TIMEOUT_MS);
            handlePingRequest(in, out);
        } catch (SocketTimeoutException ignored) {
        }
    }

    private void sendStatusResponse(DataOutputStream out) throws IOException {
        String json = buildStatusJson();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream bufOut = new DataOutputStream(buffer);
        VarInt.writeString(bufOut, json);
        bufOut.flush();
        VarInt.writePacket(0x00, buffer.toByteArray(), out);
    }

    private void handlePingRequest(DataInputStream in, DataOutputStream out) throws IOException {
        VarInt.readVarInt(in);
        int packetId = VarInt.readVarInt(in);

        if (packetId == 0x01) {
            long payload = in.readLong();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream bufOut = new DataOutputStream(buffer);
            bufOut.writeLong(payload);
            bufOut.flush();
            VarInt.writePacket(0x01, buffer.toByteArray(), out);
        }
    }

    /**
     * 处理玩家加入（登录）请求，实现"像域名直连一样"进入目标服务器：
     * <ul>
     *   <li>未配置 {@code redirectServer}：仅发送普通拒绝提示（不跳转）。</li>
     *   <li>配置了 {@code redirectServer}：对<strong>任意版本</strong>客户端建立 TCP 透传隧道——
     *       把客户端原始握手+登录包原样转发给目标服务器，之后双向原样搬运字节流。
     *       客户端体验等同于直接连接目标服务器：加密/压缩/AuthMe 登录界面等都发生在目标服务器上，
     *       不再依赖 1.20.5+ 的官方 transfer（旧客户端不会再把
     *       {@code multiplayer.disconnect.transfer} 当作断开提示显示）。</li>
     * </ul>
     */
    private void handleLogin(Socket socket, DataInputStream in, DataOutputStream out,
                             byte[] rawHandshake) throws IOException {
        byte[] rawLoginStart = readRawPacket(in);
        String username = parseLoginUsername(rawLoginStart);

        String redirect = config.redirectServer == null ? "" : config.redirectServer.trim();
        if (redirect.isEmpty()) {
            sendKick(out, buildKickJson(username));
            LOG.info("[MOTD] 玩家 " + username + " 尝试登录，已拒绝");
            return;
        }

        String[] hp = splitHostPort(redirect);
        int port = parsePort(hp[1]);

        // 防呆：目标指向本 MOTD 服务自身会造成透传死循环
        if (port == config.port && isLocalHost(hp[0])) {
            sendKick(out, buildTextKickJson("§credirect_server 配置错误：目标与本 MOTD 服务相同，请检查 application.yml。"));
            return;
        }

        relayLogin(socket, in, out, rawHandshake, rawLoginStart, hp[0], port, username);
    }

    /** 发送一条 Login Disconnect（登录阶段踢出）消息。 */
    private void sendKick(DataOutputStream out, String kickJson) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream bufOut = new DataOutputStream(buffer);
        VarInt.writeString(bufOut, kickJson);
        bufOut.flush();
        VarInt.writePacket(0x00, buffer.toByteArray(), out);
    }

    /**
     * 构建普通登录拒绝（kick）消息（无跳转语义）。
     */
    private String buildKickJson(String username) {
        int online = config.onlineCountProvider != null
                ? Math.max(0, config.onlineCountProvider.get()) : 0;

        String joinMsg = config.joinMessage;
        if (joinMsg == null || joinMsg.isBlank()) {
            joinMsg = "§c§l此服务器非 Minecraft 服务器\n§7这是一个修仙游戏API服务器\n\n§e请使用专用客户端连接";
        }

        joinMsg = joinMsg.replace("{username}", username)
                .replace("{online}", String.valueOf(online))
                .replace("{max}", String.valueOf(config.maxPlayers))
                .replace("{delay}", String.valueOf(config.redirectDelaySeconds))
                .replace("{server}", config.redirectServer != null ? config.redirectServer : "");

        JsonObject root = new JsonObject();
        root.addProperty("text", joinMsg);
        return root.toString();
    }

    /** 构建纯文本 kick 消息（透传失败等场景）。 */
    private static String buildTextKickJson(String message) {
        JsonObject root = new JsonObject();
        root.addProperty("text", message);
        return root.toString();
    }

    /**
     * TCP 透传：把玩家连接"无缝接入"目标服务器，效果等同玩家直接连目标服务器地址。
     * <p>把缓存的原始握手包 + 登录包原样发给目标服务器，随后双向转发字节流。
     * 后续的加密/压缩/登录成功/配置/游戏等全部由两端自行协商，本层只做字节搬运，
     * 因此对任意客户端版本与任意模式（正版/离线/AuthMe 登录界面）都透明可用。</p>
     */
    private void relayLogin(Socket client, DataInputStream in, DataOutputStream out,
                            byte[] rawHandshake, byte[] rawLoginStart,
                            String host, int port, String username) {
        Socket target = new Socket();
        long startedAt = System.currentTimeMillis();
        try {
            target.connect(new InetSocketAddress(host, port), RELAY_CONNECT_TIMEOUT_MS);
            target.setTcpNoDelay(true);
            target.setSoTimeout(0);
            target.setKeepAlive(true);
            // 透传阶段允许长时间空闲（例如等待 AuthMe 登录输入），取消 500ms 的短超时
            client.setSoTimeout(0);
            client.setKeepAlive(true);

            OutputStream targetOut = target.getOutputStream();
            InputStream targetIn = target.getInputStream();

            targetOut.write(rawHandshake);
            targetOut.write(rawLoginStart);
            targetOut.flush();

            LOG.info(String.format("[MOTD] 玩家 %s 已透传接入目标服务器 %s:%d", username, host, port));

            // 目标服务器 -> 客户端（独立线程；任一侧先断开时关闭目标连接，
            // 使另一方向的泵随之结束，避免泄漏）
            threadPool.submit(() -> {
                try {
                    pump(targetIn, out, "目标->玩家");
                } finally {
                    closeQuietly(target);
                }
            });
            // 客户端 -> 目标服务器（当前线程）
            pump(in, targetOut, "玩家->目标");
            double seconds = (System.currentTimeMillis() - startedAt) / 1000.0;
            LOG.info(String.format("[MOTD] 玩家 %s 与目标服务器 %s:%d 的透传连接已结束（持续 %.1f 秒）",
                    username, host, port, seconds));
        } catch (IOException e) {
            LOG.warn(String.format("[MOTD] 透传连接 %s:%d 失败: %s", host, port, e.getMessage()));
            try {
                sendKick(out, buildTextKickJson(
                        "§c主服务器暂时无法连接（" + host + ":" + port + "），请稍后重试。"));
            } catch (IOException ignored) {
            }
        } finally {
            closeQuietly(target);
            closeQuietly(client);
        }
    }

    /**
     * 单向字节泵：把 src 的内容原样写入 dst，任一端关闭后结束。
     * <p>结束时记录是哪一侧先断开（对端关闭=EOF；本侧异常=读到/写出错误），
     * 便于远程排查"进服后几秒断线"问题。</p>
     */
    private static void pump(InputStream src, OutputStream dst, String direction) {
        byte[] buffer = new byte[RELAY_BUFFER_SIZE];
        try {
            int n;
            while ((n = src.read(buffer)) != -1) {
                dst.write(buffer, 0, n);
                dst.flush();
            }
            LOG.info("[MOTD] 透传方向 " + direction + " 结束：对端关闭连接");
        } catch (IOException e) {
            LOG.info("[MOTD] 透传方向 " + direction + " 结束：" + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " " + e.getMessage() : ""));
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    /** 读取一个完整的长度前缀包（VarInt 长度 + 负载），返回含长度前缀的原始字节。 */
    private static byte[] readRawPacket(DataInputStream in) throws IOException {
        byte[] lenBytes = readRawVarInt(in);
        int len = decodeVarInt(lenBytes);
        if (len < 0 || len > MAX_PACKET_BYTES) {
            throw new IOException("数据包长度异常: " + len);
        }
        byte[] payload = new byte[len];
        in.readFully(payload);
        byte[] packet = new byte[lenBytes.length + payload.length];
        System.arraycopy(lenBytes, 0, packet, 0, lenBytes.length);
        System.arraycopy(payload, 0, packet, lenBytes.length, payload.length);
        return packet;
    }

    /** 读取原始 VarInt 字节（含终止字节）。 */
    private static byte[] readRawVarInt(DataInputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int position = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("读取 VarInt 时连接已关闭");
            }
            out.write(b);
            if ((b & 0x80) == 0) {
                break;
            }
            position += 7;
            if (position >= 35) {
                throw new IOException("VarInt 过长");
            }
        }
        return out.toByteArray();
    }

    private static int decodeVarInt(byte[] raw) {
        int value = 0;
        int position = 0;
        for (byte b : raw) {
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) {
                break;
            }
            position += 7;
        }
        return value;
    }

    /** 从握手包中解析出 packetId / 请求状态。 */
    private static ParsedHandshake parseHandshake(byte[] rawPacket) {
        try {
            DataInputStream pin = new DataInputStream(new ByteArrayInputStream(rawPacket));
            VarInt.readVarInt(pin); // 帧长度
            int packetId = VarInt.readVarInt(pin);
            if (packetId == 0xFE) {
                return new ParsedHandshake(packetId, -1);
            }
            if (packetId != 0x00) {
                return null;
            }
            VarInt.readVarInt(pin); // 客户端协议版本（透传模式下无需按版本分流，跳过即可）
            int addressLength = VarInt.readVarInt(pin);
            byte[] addressBytes = new byte[addressLength];
            pin.readFully(addressBytes);
            pin.readUnsignedShort();
            int nextState = VarInt.readVarInt(pin);
            return new ParsedHandshake(packetId, nextState);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从登录包（Login Start）中解析玩家名（尽力而为，失败返回 "?"）。 */
    private static String parseLoginUsername(byte[] rawPacket) {
        try {
            DataInputStream pin = new DataInputStream(new ByteArrayInputStream(rawPacket));
            VarInt.readVarInt(pin); // 帧长度
            int packetId = VarInt.readVarInt(pin);
            if (packetId != 0x00) {
                return "?";
            }
            int nameLength = VarInt.readVarInt(pin);
            byte[] nameBytes = new byte[nameLength];
            pin.readFully(nameBytes);
            return new String(nameBytes, "UTF-8");
        } catch (Exception e) {
            return "?";
        }
    }

    /** 端口字符串解析，非法时回退 25565。 */
    private static int parsePort(String port) {
        try {
            int p = Integer.parseInt(port.trim());
            return p > 0 && p <= 65535 ? p : 25565;
        } catch (NumberFormatException e) {
            return 25565;
        }
    }

    /** 判断主机名是否指向本机（用于防呆校验）。 */
    private static boolean isLocalHost(String host) {
        String h = host.trim().toLowerCase();
        return h.isEmpty() || h.equals("localhost") || h.equals("127.0.0.1")
                || h.equals("::1") || h.equals("0.0.0.0");
    }

    /** 解析后的握手包摘要。 */
    private static final class ParsedHandshake {
        final int packetId;
        final int nextState;

        ParsedHandshake(int packetId, int nextState) {
            this.packetId = packetId;
            this.nextState = nextState;
        }
    }

    /** 拆分 "host" / "host:port"，端口缺省 25565。 */
    private static String[] splitHostPort(String server) {
        String s = server.trim();
        int idx = s.lastIndexOf(':');
        if (idx > 0 && idx < s.length() - 1) {
            return new String[]{s.substring(0, idx), s.substring(idx + 1)};
        }
        return new String[]{s, "25565"};
    }

    private void handleLegacyPing(Socket socket, DataOutputStream out) throws IOException {
        int onlineCount = config.onlineCountProvider != null ? config.onlineCountProvider.get() : 0;
        int displayOnline = Math.min(onlineCount, config.maxPlayers);

        String response = "§3\u0000"
                + config.protocolVersion + "\u0000"
                + config.versionName + "\u0000"
                + config.motdLine1 + "\u0000"
                + displayOnline + "\u0000"
                + config.maxPlayers;

        out.write(0xFF);
        byte[] respBytes = response.getBytes("UTF-16BE");
        out.writeShort(respBytes.length);
        out.write(respBytes);
        out.flush();
        socket.close();
    }

    private String buildStatusJson() {
        int onlineCount = config.onlineCountProvider != null ? config.onlineCountProvider.get() : 0;

        JsonObject json = new JsonObject();

        JsonObject version = new JsonObject();
        version.addProperty("name", config.versionName);
        version.addProperty("protocol", config.protocolVersion);
        json.add("version", version);

        JsonObject players = new JsonObject();
        players.addProperty("max", config.maxPlayers);
        players.addProperty("online", onlineCount);

        if (onlineCount > 0) {
            JsonArray sample = new JsonArray();
            JsonObject entry = new JsonObject();
            entry.addProperty("name", String.format("§a当前在线 %d 位修仙者", onlineCount));
            entry.addProperty("id", "00000000-0000-0000-0000-000000000000");
            sample.add(entry);
            players.add("sample", sample);
        }
        json.add("players", players);

        JsonObject description = new JsonObject();
        description.addProperty("text", "");

        JsonArray extra = new JsonArray();

        JsonObject line1 = new JsonObject();
        line1.addProperty("text", config.motdLine1 + "\n");
        extra.add(line1);

        JsonObject line2 = new JsonObject();
        line2.addProperty("text", config.motdLine2);
        line2.addProperty("bold", true);
        extra.add(line2);

        JsonObject line3 = new JsonObject();
        line3.addProperty("text", config.motdLine3);
        extra.add(line3);

        description.add("extra", extra);
        json.add("description", description);

        json.addProperty("favicon", "");
        json.addProperty("enforcesSecureChat", false);
        json.addProperty("previewsChat", false);

        return json.toString();
    }

    public static class Config {
        final int port;
        final int maxPlayers;
        final String versionName;
        final int protocolVersion;
        final String motdLine1;
        final String motdLine2;
        final String motdLine3;
        final String joinMessage;
        final String redirectServer;
        final int redirectDelaySeconds;
        final OnlineCountProvider onlineCountProvider;

        private Config(Builder builder) {
            this.port = builder.port;
            this.maxPlayers = builder.maxPlayers;
            this.versionName = builder.versionName;
            this.protocolVersion = builder.protocolVersion;
            this.motdLine1 = builder.motdLine1;
            this.motdLine2 = builder.motdLine2;
            this.motdLine3 = builder.motdLine3;
            this.joinMessage = builder.joinMessage;
            this.redirectServer = builder.redirectServer;
            this.redirectDelaySeconds = builder.redirectDelaySeconds;
            this.onlineCountProvider = builder.onlineCountProvider;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static Config createDefault() {
            return builder().build();
        }

        public static class Builder {
            int port = 25565;
            int maxPlayers = 2026;
            String versionName = "§b§l修仙世界 §r§f— §eAPI v1.0";
            int protocolVersion = -1;
            String motdLine1 = "§b§l修 仙 世 界 §r§8— §e§lXiuXian Game";
            String motdLine2 = "§7";
            String motdLine3 = "§f⚔ §a境界突破 §7| §d秘境探索 §7| §e游历修仙 §f⚔";
            String joinMessage = "";
            String redirectServer = "";
            int redirectDelaySeconds = 5;
            OnlineCountProvider onlineCountProvider;

            public Builder port(int port) {
                this.port = port;
                return this;
            }

            public Builder maxPlayers(int maxPlayers) {
                this.maxPlayers = maxPlayers;
                return this;
            }

            public Builder versionName(String versionName) {
                this.versionName = versionName;
                return this;
            }

            public Builder protocolVersion(int protocolVersion) {
                this.protocolVersion = protocolVersion;
                return this;
            }

            public Builder motdLine1(String motdLine1) {
                this.motdLine1 = motdLine1;
                return this;
            }

            public Builder motdLine2(String motdLine2) {
                this.motdLine2 = motdLine2;
                return this;
            }

            public Builder motdLine3(String motdLine3) {
                this.motdLine3 = motdLine3;
                return this;
            }

            public Builder joinMessage(String joinMessage) {
                this.joinMessage = joinMessage;
                return this;
            }

            public Builder redirectServer(String redirectServer) {
                this.redirectServer = redirectServer;
                return this;
            }

            public Builder redirectDelaySeconds(int redirectDelaySeconds) {
                this.redirectDelaySeconds = redirectDelaySeconds;
                return this;
            }

            public Builder onlineCountProvider(OnlineCountProvider provider) {
                this.onlineCountProvider = provider;
                return this;
            }

            public Config build() {
                return new Config(this);
            }
        }
    }

    @FunctionalInterface
    public interface OnlineCountProvider {
        int get();
    }

    public static MinecraftMotdServer create(int port) {
        return new MinecraftMotdServer(Config.builder().port(port).build());
    }

    public static MinecraftMotdServer create(Config config) {
        return new MinecraftMotdServer(config);
    }
}
