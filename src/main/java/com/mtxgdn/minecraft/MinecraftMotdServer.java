package com.mtxgdn.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.util.GameLogger;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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

    private static final int SOCKET_TIMEOUT_MS = 500;
    private static final int PING_TIMEOUT_MS = 50;

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
        threadPool.shutdownNow();
    }

    public int getConnectionCount() {
        return connectionCount.get();
    }

    private void listen() {
        try (ServerSocket serverSocket = new ServerSocket(config.port)) {
            LOG.info(String.format("[MOTD] Minecraft MOTD 服务已启动在 0.0.0.0:%d", config.port));
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
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

            VarInt.readVarInt(in);
            int packetId = VarInt.readVarInt(in);

            if (packetId == 0xFE) {
                handleLegacyPing(socket, out);
                return;
            }

            if (packetId != 0x00) {
                return;
            }

            int clientProtocol = VarInt.readVarInt(in);
            readServerAddress(in);
            int nextState = VarInt.readVarInt(in);

            switch (nextState) {
                case 1:
                    handleStatusSequence(socket, in, out);
                    break;
                case 2:
                    handleLoginReject(in, out);
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

    private void readServerAddress(DataInputStream in) throws IOException {
        int addressLength = VarInt.readVarInt(in);
        byte[] addressBytes = new byte[addressLength];
        in.readFully(addressBytes);
        in.readUnsignedShort();
    }

    private void handleStatusSequence(Socket socket, DataInputStream in, DataOutputStream out) throws IOException {
        int packetLength = VarInt.readVarInt(in);
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
        int packetLength = VarInt.readVarInt(in);
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

    private void handleLoginReject(DataInputStream in, DataOutputStream out) throws IOException {
        VarInt.readVarInt(in);
        int packetId = VarInt.readVarInt(in);

        if (packetId != 0x00) {
            return;
        }

        int nameLength = VarInt.readVarInt(in);
        byte[] nameBytes = new byte[nameLength];
        in.readFully(nameBytes);
        String username = new String(nameBytes, "UTF-8");

        String kickMsg = "{\"text\":\"§c§l此服务器非 Minecraft 服务器\\n§7这是一个修仙游戏API服务器\\n\\n§e请使用专用客户端连接\"}";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream bufOut = new DataOutputStream(buffer);
        VarInt.writeString(bufOut, kickMsg);
        bufOut.flush();
        VarInt.writePacket(0x00, buffer.toByteArray(), out);

        LOG.info(String.format("[MOTD] 玩家 %s 尝试登录，已拒绝", username));
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
        final OnlineCountProvider onlineCountProvider;

        private Config(Builder builder) {
            this.port = builder.port;
            this.maxPlayers = builder.maxPlayers;
            this.versionName = builder.versionName;
            this.protocolVersion = builder.protocolVersion;
            this.motdLine1 = builder.motdLine1;
            this.motdLine2 = builder.motdLine2;
            this.motdLine3 = builder.motdLine3;
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
