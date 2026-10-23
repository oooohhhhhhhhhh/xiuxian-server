package com.mtxgdn.minecraft.adapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtxgdn.minecraft.VarInt;
import com.mtxgdn.util.GameLogger;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Minecraft 协议客户端，以玩家身份连接 Minecraft 服务器（仅离线模式）。
 * <p>
 * 通过 {@link McProtocolMapper} 支持多版本，自动处理配置阶段（1.20.2+）、玩家聊天包（1.19.3+）、
 * NBT 聊天组件（1.20.3+）等差异。当前重点核对版本是 <b>26.2（协议 776）</b>，
 * 字段布局逐条对照官方服务端源码：
 * <ul>
 *   <li>{@code ServerboundChatCommandPacket} 只有一个命令字符串（签名版是另一个包 0x08）；</li>
 *   <li>{@code ServerboundChatPacket} 的 {@code LastSeenMessages.Update} = VarInt 偏移 + 20 位位图 + 校验字节；</li>
 *   <li>{@code ClientboundPlayerChatPacket} 首字段是 globalIndex，聊天组件走 NBT；</li>
 *   <li>私聊用 {@code ChatType} 注册表 id 区分（配置阶段同步下来），不再靠文本猜。</li>
 * </ul>
 * 不渲染图像，仅处理聊天消息和基础协议。
 */
public class MinecraftClient implements Runnable {

    private static final GameLogger log = GameLogger.getLogger("McClient");

    // ---- 连接参数 ----
    private final String host;
    private final int port;
    private final String username;
    private final int configProtocol;   // 用户配置的协议版本（0 = 自动检测）

    // ---- 运行时协议映射 ----
    private McProtocolMapper proto;

    // ---- 连接状态 ----
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean inConfigPhase = false;   // 配置阶段（1.20.2+）进行中，此时不能发 PLAY 阶段的包
    private volatile boolean reconfigureRequested = false;   // 服务端要求重新进入配置阶段（/reload）
    private volatile int compressionThreshold = -1;  // -1 = 未启用压缩
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread readThread;

    // ---- 26.2 聊天类型注册表（索引 + 1 = 线缆上的 holder id）----
    private final List<String> chatTypeIds = new CopyOnWriteArrayList<>();
    private volatile boolean warnedUnknownChatType = false;
    private volatile boolean warnedMissingChatTypes = false;

    // ---- PLAY 状态就绪信号 ----
    private volatile boolean playReady = false;
    private final CountDownLatch playReadyLatch = new CountDownLatch(1);
    private final AtomicBoolean disconnectNotified = new AtomicBoolean(false);

    // ---- 回调 ----
    private Consumer<String> onLoginSuccess;
    private Consumer<String> onKick;
    private BiConsumer<String, String> onChatMessage;
    private BiConsumer<String, String> onTellMessage;
    private Consumer<String> onDisconnected;

    // ---- 登录结果 ----
    private String playerUuid;
    private String playerName;

    public MinecraftClient(String host, int port, String username, int configProtocol) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.configProtocol = configProtocol;
    }

    // ==================== 回调设置 ====================

    public void setOnLoginSuccess(Consumer<String> cb) { this.onLoginSuccess = cb; }
    public void setOnKick(Consumer<String> cb) { this.onKick = cb; }
    public void setOnChatMessage(BiConsumer<String, String> cb) { this.onChatMessage = cb; }
    public void setOnTellMessage(BiConsumer<String, String> cb) { this.onTellMessage = cb; }
    public void setOnDisconnected(Consumer<String> cb) { this.onDisconnected = cb; }

    public String getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }
    public boolean isRunning() { return running.get(); }

    // ==================== 连接与登录 ====================

    public boolean connect() {
        try {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(host, port), 10000);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
            return true;
        } catch (IOException e) {
            log.error("连接失败 " + host + ":" + port + " - " + e.getMessage());
            return false;
        }
    }

    /** 自动检测版本并登录 */
    public boolean login() {
        // 自动检测协议版本（Status Ping 的握手协议号只是占位，服务端不会据此拒绝）
        if (configProtocol <= 0) {
            proto = McProtocolMapper.detect(host, port, 763);
        }
        if (proto == null) {
            proto = McProtocolMapper.of(configProtocol > 0 ? configProtocol : 763);
        }
        log.info("使用协议版本: " + proto.protocol + " (" + proto.versionLabel + ")");
        if (proto.hasConfigPhase) log.info("  需要配置阶段");
        if (proto.isLegacyChat) log.info("  旧版聊天格式");
        // 不需要配置阶段的版本，登录成功即 PLAY
        playReady = !proto.hasConfigPhase;

        try {
            // ===== Handshake =====
            ByteArrayOutputStream hsb = new ByteArrayOutputStream();
            DataOutputStream hOut = new DataOutputStream(hsb);
            VarInt.writeVarInt(proto.protocol, hOut);
            VarInt.writeString(hOut, host);
            // 端口必须用无符号短整型写入
            hOut.write((port >> 8) & 0xFF);
            hOut.write(port & 0xFF);
            VarInt.writeVarInt(2, hOut);  // next state: login
            hOut.flush();
            sendPacketRaw(McProtocolMapper.HANDSHAKE, hsb.toByteArray());

            // ===== Login Start =====
            ByteArrayOutputStream lsb = new ByteArrayOutputStream();
            DataOutputStream lOut = new DataOutputStream(lsb);
            VarInt.writeString(lOut, username);
            if (proto.hasLoginUuid) {
                // 1.20.5+ 需要 UUID
                String offlineUuid = UUID.nameUUIDFromBytes(
                        ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)).toString();
                lOut.writeLong(UUID.fromString(offlineUuid).getMostSignificantBits());
                lOut.writeLong(UUID.fromString(offlineUuid).getLeastSignificantBits());
            }
            lOut.flush();
            sendPacketRaw(McProtocolMapper.LOGIN_START, lsb.toByteArray());
            out.flush();

            // ===== 读取登录响应 =====
            // 可能收到: Set Compression (0x03), Login Success (0x02), Login Disconnect (0x00)
            boolean loginSuccess = false;

            while (!loginSuccess) {
                Object[] packet = readPacket();
                int packetId = (int) packet[0];
                byte[] data = (byte[]) packet[1];

                if (packetId == 0x03) {
                    // Set Compression: VarInt threshold
                    DataInputStream d = new DataInputStream(new ByteArrayInputStream(data));
                    compressionThreshold = VarInt.readVarInt(d);
                    log.debug("启用压缩: threshold=" + compressionThreshold);
                } else if (packetId == McProtocolMapper.LOGIN_SUCCESS) {
                    DataInputStream dataIn = new DataInputStream(new ByteArrayInputStream(data));

                    if (proto.hasLoginUuid) {
                        // 1.20.5+: UUID 是原始 128-bit（2 个 long）
                        long most = dataIn.readLong();
                        long least = dataIn.readLong();
                        playerUuid = new UUID(most, least).toString();
                    } else {
                        // 旧版: UUID 是字符串
                        playerUuid = readString(dataIn);
                    }
                    playerName = readString(dataIn);

                    // 1.19+ 登录成功后有 properties 数组
                    if (!proto.isLegacyChat) {
                        int propCount = VarInt.readVarInt(dataIn);
                        for (int i = 0; i < propCount; i++) {
                            readString(dataIn); // name
                            readString(dataIn); // value
                            if (dataIn.readBoolean()) {
                                readString(dataIn); // signature
                            }
                        }
                    }

                    log.info("登录成功: " + playerName + " (UUID: " + playerUuid + ")");
                    loginSuccess = true;

                    // 1.20+: 发送 Login Acknowledged 进入配置阶段
                    if (proto.hasLoginAck) {
                        sendPacketRaw(McProtocolMapper.LOGIN_ACK, new byte[0]);
                    }
                } else if (packetId == McProtocolMapper.LOGIN_DISCONNECT) {
                    DataInputStream dataIn = new DataInputStream(new ByteArrayInputStream(data));
                    String reason = readString(dataIn);
                    log.debug("登录拒绝原始原因: " + reason);
                    String readable = parseChatJson(reason);
                    log.warn("登录被拒绝: " + readable);
                    if (onKick != null) onKick.accept(readable);
                    disconnect();
                    return false;
                } else {
                    log.warn("登录阶段收到未知包: 0x" + Integer.toHexString(packetId));
                    disconnect();
                    return false;
                }
            }

            running.set(true);

            // 读取线程统一跑 run()：配置阶段（1.20.2+）与 PLAY 阶段的切换、以及服务端
            // 中途要求重新配置（/reload）都由它调度。
            readThread = new Thread(this, "McClient-Reader-" + username);
            readThread.setDaemon(true);
            readThread.start();

            if (onLoginSuccess != null) {
                onLoginSuccess.accept(playerUuid);
            }
            return true;
        } catch (IOException e) {
            log.error("登录失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            disconnect();
            return false;
        }
    }

    // ==================== 配置阶段 (1.20.2+) ====================

    /** 跑一次配置阶段；返回 false 表示连接已经结束（被踢/超时/协议错误）。 */
    private boolean runConfigPhase() {
        try {
            log.info("进入配置阶段...");
            // 1.20-1.20.4 没有 Known Packs，直接发送 ClientInformation
            if (!proto.hasKnownPacks) {
                sendClientInfo();
            }

            boolean configDone = false;
            while (running.get() && !configDone) {
                Object[] pkt = readPacket();
                int packetId = (int) pkt[0];
                byte[] data = (byte[]) pkt[1];

                if (packetId == proto.s2cConfigKnownPacks) {
                    // 服务端发送 Known Packs → 客户端回复 ClientInformation + 空 Known Packs
                    sendClientInfo();
                } else if (packetId == proto.s2cConfigRegistryData) {
                    // 注册表同步：只需要 chat_type（私聊判定要靠它）
                    handleRegistryData(data);
                } else if (packetId == proto.s2cConfigFinish) {
                    // 服务端配置完成 → 客户端确认
                    sendConfigAck();
                    configDone = true;
                } else if (packetId == proto.s2cConfigKeepAlive) {
                    DataInputStream d = new DataInputStream(new ByteArrayInputStream(data));
                    long id = d.readLong();
                    sendKeepAlivePacket(proto.c2sConfigKeepAlive, id);
                } else if (packetId == proto.s2cConfigPing) {
                    // 延迟探测：不回包不影响连接，但回一下更接近真实客户端
                    sendPong(proto.c2sConfigPong, data);
                } else if (packetId == proto.s2cConfigResourcePackPush) {
                    // 不下载资源包。必须回一个终态，否则服务端会卡在「等资源包」这个配置任务上，
                    // 永远不会发 Finish Configuration。
                    handleResourcePackPush(data, proto.c2sConfigResourcePack);
                } else if (packetId == proto.s2cConfigCodeOfConduct) {
                    // 26.2 新增的行为准则：同样是「不回就卡住」的配置任务
                    sendPacketRaw(proto.c2sConfigAcceptCodeOfConduct, new byte[0]);
                } else if (packetId == proto.s2cConfigDisconnect) {
                    handleDisconnect(data);
                    return false;
                } else {
                    // 忽略其他配置包（plugin_message, update_tags 等）
                }
            }

            if (!configDone) {
                return false;
            }
            log.info("配置阶段完成，进入 PLAY 状态");
            return true;
        } catch (EOFException e) {
            log.info("服务器断开连接 (配置阶段)");
            return false;
        } catch (IOException e) {
            if (running.get()) log.error("配置阶段异常: " + e.getMessage());
            return false;
        }
    }

    private void sendClientInfo() throws IOException {
        // 1.20.5+ 格式稍有不同
        if (proto.hasKnownPacks) {
            // 1.20.5+: ClientInformation + empty known packs
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(buf);
            VarInt.writeString(d, "zh_CN");
            d.writeByte(8);       // view distance
            VarInt.writeVarInt(0, d); // chat mode: enabled
            d.writeBoolean(true);  // chat colors
            d.writeByte(0xFF);     // displayed skin parts (all)
            VarInt.writeVarInt(0, d); // main hand: left
            d.writeBoolean(false); // text filtering
            d.writeBoolean(false); // server listing
            // 1.21+ (proto >= 769) 新增 particle status
            if (proto.protocol >= 769) {
                VarInt.writeVarInt(0, d); // particle status: all
            }
            d.flush();
            sendPacketRaw(proto.c2sClientInfo, buf.toByteArray());

            // empty known packs（表示客户端不需要任何已知包，服务端发送全部）
            ByteArrayOutputStream kpBuf = new ByteArrayOutputStream();
            DataOutputStream kpOut = new DataOutputStream(kpBuf);
            VarInt.writeVarInt(0, kpOut); // 0 known packs
            kpOut.flush();
            sendPacketRaw(proto.c2sConfigKnownPacks, kpBuf.toByteArray());
        } else {
            // 1.20.2-1.20.4: ClientInformation
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(buf);
            VarInt.writeString(d, "zh_CN");
            d.writeByte(8);        // view distance
            VarInt.writeVarInt(0, d); // chat mode
            d.writeBoolean(true);   // chat colors
            d.writeByte(0xFF);      // skin parts
            VarInt.writeVarInt(0, d); // main hand
            d.writeBoolean(false);  // text filtering
            d.writeBoolean(false);  // server listing
            d.flush();
            sendPacketRaw(proto.c2sClientInfo, buf.toByteArray());
        }
        out.flush();
    }

    private void sendConfigAck() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        d.flush();
        sendPacketRaw(proto.c2sConfigFinish, buf.toByteArray());
        out.flush();
    }

    /** 回一个 int id 的 Pong（延迟探测）。 */
    private void sendPong(int packetId, byte[] pingData) throws IOException {
        if (packetId <= 0) {
            return;
        }
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(pingData));
        int id = d.readInt();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out2 = new DataOutputStream(buf);
        out2.writeInt(id);
        out2.flush();
        sendPacketRaw(packetId, buf.toByteArray());
    }

    /**
     * 处理服务端的资源包推送。
     * <p>
     * 这里不下载资源包，直接回 {@code DECLINED}（Action 序号 1，是终态）。
     * 必须回一个终态：服务端在配置阶段会挂一个 {@code server_resource_pack} 任务，
     * 收不到终态就永远不会发 Finish Configuration，连接会一直卡在配置阶段。
     * 如果服务端把资源包标记为必需，它收到 DECLINED 会主动断开——那也比静默卡死好排查。
     */
    private void handleResourcePackPush(byte[] data, int responsePacketId) throws IOException {
        if (responsePacketId <= 0) {
            return;
        }
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(data));
        long uuidMsb = d.readLong();
        long uuidLsb = d.readLong();
        String url = readString(d);
        readString(d);                 // hash
        boolean required = d.readBoolean();

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out2 = new DataOutputStream(buf);
        out2.writeLong(uuidMsb);
        out2.writeLong(uuidLsb);
        VarInt.writeVarInt(1, out2);   // Action.DECLINED
        out2.flush();
        sendPacketRaw(responsePacketId, buf.toByteArray());
        log.info("收到资源包推送" + (required ? "（服务端标记为必需）" : "") + "，已拒绝: " + url);
    }

    /**
     * 配置阶段的注册表同步。只关心 {@code minecraft:chat_type}：
     * 私聊判定需要「线缆上的 holder id → 聊天类型名」这张表，而它的顺序由服务端的数据包决定，
     * 不能写死。其余注册表连读都不读（包自带长度，跳过即可）。
     */
    private void handleRegistryData(byte[] data) {
        try {
            List<String> ids = parseChatTypeRegistry(data);
            if (ids == null) {
                return;   // 不是 chat_type 注册表，丢掉
            }
            chatTypeIds.clear();
            chatTypeIds.addAll(ids);
            log.info("已同步聊天类型注册表 (" + ids.size() + " 项)");
            log.debug("聊天类型 id 映射: " + ids);
        } catch (IOException e) {
            log.warn("解析注册表同步包失败: " + e.getMessage());
        }
    }

    /**
     * 从 {@code ClientboundRegistryDataPacket} 载荷里取出 chat_type 注册表的条目名（顺序即 id 顺序）。
     * 不是 chat_type 注册表时返回 null——其余注册表连读都不读（包自带长度，跳过即可）。
     */
    static List<String> parseChatTypeRegistry(byte[] data) throws IOException {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(data));
        String registryKey = readStringStatic(d);
        if (!"minecraft:chat_type".equals(registryKey)) {
            return null;
        }
        int count = VarInt.readVarInt(d);
        if (count < 0) {
            throw new IOException("注册表条目数非法: " + count);
        }
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(readStringStatic(d));
            if (d.readBoolean()) {          // Optional<Tag> data
                McNbt.skipTag(d);
            }
        }
        return ids;
    }

    /** 解析并上报被踢原因（26.2 的 Play/Config 踢出原因是 NBT 组件，登录阶段才是 JSON）。 */
    private void handleDisconnect(byte[] data) {
        String readable;
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(data));
            readable = stripColor(proto.componentsAreNbt ? McNbt.toPlainText(McNbt.readTag(d)) : parseChatJson(readString(d)));
        } catch (IOException e) {
            readable = "<无法解析的断开原因>";
        }
        if (readable.isEmpty()) {
            readable = "<服务器未提供原因>";
        }
        log.warn("被服务器断开: " + readable);
        if (onKick != null) onKick.accept(readable);
        disconnect();
    }

    // ==================== 发送消息 ====================

    /** 发送聊天消息 */
    public void sendChatMessage(String message) {
        if (!running.get()) return;
        if (!playReady) {
            // 配置阶段发 PLAY 阶段的包会被服务端按配置阶段的包号解析，直接断连
            log.warn((inConfigPhase ? "仍在配置阶段" : "尚未进入 PLAY 状态") + "，聊天消息被丢弃: " + message);
            return;
        }
        try {
            sendPacketRaw(proto.c2sChatMessage, encodeChatPayload(proto, message, System.currentTimeMillis()));
        } catch (IOException e) {
            log.error("发送聊天失败: " + e.getMessage());
        }
    }

    /**
     * 发送指令（不带前导斜杠，与真实客户端一致：{@code ChatScreen} 里
     * {@code sendCommand(msg.substring(1))}，包里的字符串同样没有 "/"）。
     */
    public void sendCommand(String command) {
        if (!running.get()) return;
        if (!playReady) {
            log.warn((inConfigPhase ? "仍在配置阶段" : "尚未进入 PLAY 状态") + "，指令被丢弃: " + command);
            return;
        }
        if (proto.isLegacyChat) {
            sendChatMessage(command); // 旧版直接用 chat 包发指令
            return;
        }
        try {
            sendPacketRaw(proto.c2sChatCommand, encodeChatCommandPayload(proto, command, System.currentTimeMillis()));
        } catch (IOException e) {
            log.error("发送指令失败: " + e.getMessage());
        }
    }

    // ==================== 断开 ====================

    public void disconnect() {
        running.set(false);
        playReady = false;
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
        // 只在真正断开的那一次回调（stop() 与读取线程收尾都会走到这里）
        if (disconnectNotified.compareAndSet(false, true) && onDisconnected != null) {
            onDisconnected.accept(null);
        }
    }

    // ==================== 读取循环 ====================

    @Override
    public void run() {
        try {
            if (proto.hasConfigPhase) {
                inConfigPhase = true;
                if (!runConfigPhase()) {
                    return;
                }
                inConfigPhase = false;
                markPlayReady();
            }

            while (running.get()) {
                playReadLoop();
                if (!running.get() || !reconfigureRequested) {
                    return;
                }
                // 服务端要求重新进入配置阶段（例如 /reload 之后）
                reconfigureRequested = false;
                playReady = false;
                inConfigPhase = true;
                if (!runConfigPhase()) {
                    return;
                }
                inConfigPhase = false;
                markPlayReady();
            }
        } finally {
            running.set(false);
            disconnect();
        }
    }

    private void markPlayReady() {
        playReady = true;
        playReadyLatch.countDown();
    }

    /**
     * 等待客户端真正进入 PLAY 状态（配置阶段结束）。
     * <p>
     * 配置阶段里发 PLAY 阶段的包会被服务端按配置阶段的包号解析（例如 0x07 是
     * select_known_packs 而不是聊天指令），轻则报错重则直接断开。
     */
    public boolean awaitPlayReady(long timeoutMs) {
        if (!proto.hasConfigPhase || playReady) {
            return true;
        }
        try {
            return playReadyLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void playReadLoop() {
        try {
            while (running.get() && !reconfigureRequested) {
                Object[] pkt = readPacket();
                int packetId = (int) pkt[0];
                byte[] data = (byte[]) pkt[1];
                handlePlayPacket(packetId, data);
            }
        } catch (EOFException e) {
            log.info("服务器断开连接");
        } catch (IOException e) {
            if (running.get()) log.error("读取异常: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void handlePlayPacket(int packetId, byte[] data) {
        try {
            // === Keep Alive ===
            if (packetId == proto.s2cKeepAlive) {
                DataInputStream d = new DataInputStream(new ByteArrayInputStream(data));
                long id = d.readLong();
                sendKeepAlivePacket(proto.c2sKeepAlive, id);
                return;
            }

            // === Disconnect（26.2：原因是 NBT 组件，不再是 JSON）===
            if (packetId == proto.s2cDisconnect) {
                handleDisconnect(data);
                return;
            }

            // === Ping / Pong ===
            if (proto.s2cPing > 0 && packetId == proto.s2cPing) {
                sendPong(proto.c2sPong, data);
                return;
            }

            // === 重生：必须回 player_loaded，否则服务端一直认为客户端没加载完 ===
            if (proto.s2cRespawn > 0 && packetId == proto.s2cRespawn) {
                sendPacketRaw(proto.c2sPlayerLoaded, new byte[0]);
                return;
            }

            // === 服务端要求重新进入配置阶段 ===
            if (proto.s2cStartConfiguration > 0 && packetId == proto.s2cStartConfiguration) {
                sendPacketRaw(proto.c2sConfigAcknowledged, new byte[0]);
                reconfigureRequested = true;
                return;
            }

            // === System Chat ===
            if (packetId == proto.s2cSystemChat) {
                handleSystemChat(data);
                return;
            }

            // === Disguised Chat (1.19.2+，离线模式的私聊就走这个包) ===
            if (proto.s2cDisguisedChat > 0 && packetId == proto.s2cDisguisedChat) {
                handleDisguisedChat(data);
                return;
            }

            // === Player Chat (1.19.3+) ===
            if (proto.hasPlayerChat && packetId == proto.s2cPlayerChat) {
                handlePlayerChat(data);
                return;
            }

        } catch (IOException e) {
            log.debug("解析包 0x" + Integer.toHexString(packetId) + " 失败: " + e.getMessage());
        }
    }

    // ==================== 聊天消息处理 ====================

    /** 一条聊天包解出来的内容。 */
    record ChatMessage(int chatTypeId, String sender, String target, String content, String rawJson) {
    }

    private void handleSystemChat(byte[] data) throws IOException {
        ChatMessage msg = decodeSystemChat(proto, data);
        String text = msg.content();
        if (text.isEmpty()) return;

        // 旧协议：私聊可能是 JSON translate 形式
        if (msg.rawJson() != null) {
            JsonElement el;
            try {
                el = JsonParser.parseString(msg.rawJson());
            } catch (Exception e) {
                processPlainTextTell(text);
                return;
            }

            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                String translate = obj.has("translate") ? obj.get("translate").getAsString() : "";

                if ("commands.message.display.incoming".equals(translate)) {
                    var with = obj.getAsJsonArray("with");
                    if (with != null && with.size() >= 2) {
                        String sender = parseChatJsonElement(with.get(0));
                        String inner = parseChatJsonElement(with.get(1));
                        if (onTellMessage != null) onTellMessage.accept(sender, inner);
                        return;
                    }
                }
                if ("commands.message.display.outgoing".equals(translate)) {
                    return; // 自己发出的 tell，忽略
                }
            }
        }

        processPlainTextTell(text);
    }

    private void handleDisguisedChat(byte[] data) throws IOException {
        ChatMessage msg = decodeDisguisedChat(proto, data);
        log.debug("DisguisedChat type=" + chatTypeName(msg.chatTypeId())
                + " sender=" + msg.sender() + " target=" + msg.target());
        if (msg.content().isEmpty()) {
            return;
        }

        if (isIncomingTell(chatTypeIds, msg.chatTypeId(), msg.sender(), playerName, this)) {
            if (onTellMessage != null) onTellMessage.accept(msg.sender(), msg.content());
        } else if (isOutgoingTell(chatTypeIds, msg.chatTypeId())) {
            // 自己发出的私聊回显，忽略
        } else if (onChatMessage != null) {
            onChatMessage.accept(msg.sender(), msg.content());
        }
    }

    private void handlePlayerChat(byte[] data) throws IOException {
        ChatMessage msg = decodePlayerChat(proto, data);
        log.debug("PlayerChat type=" + chatTypeName(msg.chatTypeId())
                + " sender=" + msg.sender() + " target=" + msg.target());
        if (msg.content().isEmpty()) {
            return;
        }

        if (isIncomingTell(chatTypeIds, msg.chatTypeId(), msg.sender(), playerName, this)) {
            if (onTellMessage != null) onTellMessage.accept(msg.sender(), msg.content());
        } else if (isOutgoingTell(chatTypeIds, msg.chatTypeId())) {
            // 自己发出的私聊回显，忽略
        } else if (onChatMessage != null) {
            onChatMessage.accept(msg.sender(), msg.content());
        }
    }

    // ==================== 纯编解码（可单测）====================

    /** 26.2 的 ServerboundChatCommandPacket 载荷：只有一个命令字符串。 */
    static byte[] encodeChatCommandPayload(McProtocolMapper proto, String command, long nowMillis) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        VarInt.writeString(d, command);
        if (!proto.plainChatCommand) {
            d.writeLong(nowMillis);
            d.writeLong(0); // salt
            if (proto.hasLoginUuid) {
                d.writeBoolean(false);
            }
            VarInt.writeVarInt(0, d); // ArgumentSignatures
            VarInt.writeVarInt(0, d); // LastSeenMessages（旧格式）
            if (!proto.hasLoginUuid) {
                d.writeBoolean(false);
            }
        }
        d.flush();
        return buf.toByteArray();
    }

    /** 26.2 的 ServerboundChatPacket 载荷：message + Instant + salt + 无签名 + Update。 */
    static byte[] encodeChatPayload(McProtocolMapper proto, String message, long nowMillis) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        VarInt.writeString(d, message);
        if (proto.isLegacyChat) {
            d.flush();
            return buf.toByteArray();
        }
        d.writeLong(nowMillis);
        d.writeLong(0);            // salt
        d.writeBoolean(false);     // 无签名
        if (proto.chatAckV2) {
            // 26.2: LastSeenMessages.Update = VarInt 偏移 + 20 位位图（3 字节）+ 校验字节
            VarInt.writeVarInt(0, d);
            d.write(new byte[3]);
            d.writeByte(0);        // 0 = 忽略校验
        } else {
            VarInt.writeVarInt(0, d);
            if (!proto.hasLoginUuid) {
                d.writeBoolean(false);
            }
        }
        d.flush();
        return buf.toByteArray();
    }

    /** ClientboundSystemChatPacket：Component(NBT 或 JSON) + overlay(bool)。 */
    static ChatMessage decodeSystemChat(McProtocolMapper proto, byte[] payload) throws IOException {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(payload));
        String json = null;
        String text;
        if (proto.componentsAreNbt) {
            text = stripColor(McNbt.toPlainText(McNbt.readTag(d)));
        } else {
            json = readStringStatic(d);
            text = stripColor(parseChatJson(json));
        }
        if (!proto.isLegacyChat && d.available() > 0) {
            d.readBoolean();       // overlay
        }
        return new ChatMessage(-1, null, null, text, json);
    }

    /** ClientboundDisguisedChatPacket：Component + ChatType.Bound(holderId, name, 可选 target)。 */
    static ChatMessage decodeDisguisedChat(McProtocolMapper proto, byte[] payload) throws IOException {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(payload));
        String content = readComponentTextStatic(proto, d);
        int holderId = VarInt.readVarInt(d);
        String sender = readComponentTextStatic(proto, d);
        String target = d.readBoolean() ? readComponentTextStatic(proto, d) : null;
        return new ChatMessage(holderId, sender, target, content, null);
    }

    /**
     * ClientboundPlayerChatPacket。26.2 与 1.19.3~1.21.x 的字段布局不同：
     * 26.2 多了开头的 globalIndex，签名是固定 256 字节，LastSeenMessages 是「id+1 / 完整签名」。
     */
    static ChatMessage decodePlayerChat(McProtocolMapper proto, byte[] payload) throws IOException {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(payload));

        if (!proto.chatAckV2) {
            // 旧布局：sender UUID, index, 签名, body, previous messages, unsigned, filter, chatType
            d.skipBytes(16);
            VarInt.readVarInt(d);
            if (d.readBoolean()) {
                int sigLen = VarInt.readVarInt(d);
                if (sigLen > 0) d.skipBytes(sigLen);
            }
            String body = readStringStatic(d);
            d.readLong();
            d.readLong();
            int prevCount = VarInt.readVarInt(d);
            for (int i = 0; i < prevCount; i++) {
                d.skipBytes(16);
                if (d.readBoolean()) {
                    int sLen = VarInt.readVarInt(d);
                    if (sLen > 0) d.skipBytes(sLen);
                }
            }
            String unsigned = null;
            if (d.readBoolean()) {
                unsigned = readStringStatic(d);
            }
            int filterType = VarInt.readVarInt(d);
            if (filterType == 2) {
                int bitsLen = VarInt.readVarInt(d);
                if (bitsLen > 0) d.skipBytes(bitsLen);
            }
            int holderId = VarInt.readVarInt(d);
            String sender = readStringStatic(d);
            String target = d.readBoolean() ? readStringStatic(d) : null;
            String message = (unsigned != null && !unsigned.isEmpty()) ? parseChatJson(unsigned) : parseChatJson(body);
            return new ChatMessage(holderId, stripColor(sender), stripColor(target), stripColor(message), null);
        }

        VarInt.readVarInt(d);              // globalIndex
        d.skipBytes(16);                   // sender UUID
        VarInt.readVarInt(d);              // index
        if (d.readBoolean()) {             // MessageSignature 固定 256 字节
            d.skipBytes(256);
        }
        String body = readStringStatic(d); // Packed.content
        d.readLong();                      // timestamp
        d.readLong();                      // salt
        int packedCount = VarInt.readVarInt(d);   // LastSeenMessages.Packed
        for (int i = 0; i < packedCount; i++) {
            int packedId = VarInt.readVarInt(d);
            if (packedId == 0) {           // id - 1 == -1 → 完整签名
                d.skipBytes(256);
            }
        }
        String unsigned = null;
        if (d.readBoolean()) {             // Optional<Component>
            unsigned = readComponentTextStatic(proto, d);
        }
        int filterType = VarInt.readVarInt(d);    // FilterMask.Type
        if (filterType == 2) {                    // PARTIALLY_FILTERED → BitSet(long[])
            int longCount = VarInt.readVarInt(d);
            if (longCount > 0) {
                d.skipBytes(longCount * 8);
            }
        }
        int holderId = VarInt.readVarInt(d);      // ChatType.Bound
        String sender = readComponentTextStatic(proto, d);
        String target = d.readBoolean() ? readComponentTextStatic(proto, d) : null;

        String message = (unsigned != null && !unsigned.isEmpty()) ? unsigned : body;
        return new ChatMessage(holderId, sender, target, message, null);
    }

    private static String readComponentTextStatic(McProtocolMapper proto, DataInputStream d) throws IOException {
        if (proto.componentsAreNbt) {
            return stripColor(McNbt.toPlainText(McNbt.readTag(d)));
        }
        return stripColor(parseChatJson(readStringStatic(d)));
    }

    private static String readStringStatic(DataInputStream d) throws IOException {
        int len = VarInt.readVarInt(d);
        if (len < 0 || len > d.available()) {
            throw new IOException("字符串长度非法: " + len);
        }
        byte[] bytes = new byte[len];
        d.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ==================== 聊天类型判定（26.2）====================

    private static final String CT_MSG_INCOMING = "minecraft:msg_command_incoming";
    private static final String CT_MSG_OUTGOING = "minecraft:msg_command_outgoing";
    private static final String CT_TEAM_MSG_INCOMING = "minecraft:team_msg_command_incoming";
    private static final String CT_TEAM_MSG_OUTGOING = "minecraft:team_msg_command_outgoing";

    /**
     * 把 {@code ChatType.Bound} 里的 holder id 还原成注册表里的名字。
     * 线缆上是「注册表 id + 1」，0 表示内联定义（这里不认识，返回 null）。
     */
    private String chatTypeName(int holderId) {
        if (holderId <= 0) {
            return null;
        }
        int index = holderId - 1;
        if (index >= chatTypeIds.size()) {
            if (!warnedUnknownChatType) {
                warnedUnknownChatType = true;
                log.warn("收到未知的聊天类型 id " + holderId + "（已同步 " + chatTypeIds.size() + " 项）");
            }
            return null;
        }
        return chatTypeIds.get(index);
    }

    static String chatTypeName(List<String> chatTypeIds, int holderId) {
        if (holderId <= 0) {
            return null;
        }
        int index = holderId - 1;
        return index < chatTypeIds.size() ? chatTypeIds.get(index) : null;
    }

    /**
     * 是否是需要处理的私聊。
     * <p>
     * 正常情况下用注册表里的聊天类型名精确判定（{@code /msg} 的接收方是
     * {@code msg_command_incoming}，且没有目标名；只有发给发送者的回显才是
     * {@code msg_command_outgoing}，带目标名）。所以「有没有目标名」区分不了收发，
     * 必须看聊天类型。
     * <p>
     * 万一注册表没同步成功，退化为「非本人发送的消息都当私聊」——宁可多认，
     * 也不能让玩家私聊石沉大海。
     */
    static boolean isIncomingTell(List<String> chatTypeIds, int holderId, String sender,
                                  String selfName, MinecraftClient client) {
        String chatType = chatTypeName(chatTypeIds, holderId);
        if (chatType == null) {
            if (chatTypeIds.isEmpty() && client != null && !client.warnedMissingChatTypes) {
                client.warnedMissingChatTypes = true;
                log.warn("没有收到聊天类型注册表，私聊判定退化为「非本人发送的消息」");
            }
            return sender != null && selfName != null && !sender.equalsIgnoreCase(selfName);
        }
        return CT_MSG_INCOMING.equals(chatType) || CT_TEAM_MSG_INCOMING.equals(chatType);
    }

    static boolean isOutgoingTell(List<String> chatTypeIds, int holderId) {
        String chatType = chatTypeName(chatTypeIds, holderId);
        return CT_MSG_OUTGOING.equals(chatType) || CT_TEAM_MSG_OUTGOING.equals(chatType);
    }

    /**
     * 单个数据包的长度上限（压缩前/压缩后都适用）。
     *
     * <p>Minecraft 协议本身把包长限制在 3 字节 VarInt（最大 2,097,151 字节），
     * 原版客户端也按这个上限拒包，这里用同样的值作为服务端侧上限。</p>
     *
     * <p><b>为什么必须有这个上限</b>：{@code packetLen} 与压缩包里的"未压缩长度"
     * 都是<b>对端给的数字</b>，而代码直接拿它们去 {@code new byte[...]}。
     * 一个恶意/异常的 MC 服务端（或中间人、或损坏的流）只要声明
     * {@code dataLen = 2GB}，就能让我们在收到任何实际数据之前先 {@code OutOfMemoryError}
     * —— 整个 Java 进程直接死掉，连日志都来不及写。这是典型的"用输入当分配长度"。</p>
     */
    static final int MAX_PACKET_SIZE = 2_097_151;

    /** 从纯文本检测私聊格式 (whispers to you) */
    private void processPlainTextTell(String text) {
        if (text.contains("whispers to you:") || text.contains("whispers:")) {
            String[] parts = text.split("whispers to you:|whispers:", 2);
            if (parts.length >= 2) {
                String sender = stripColor(parts[0]).trim();
                String msg = parts[1].trim();
                if (onTellMessage != null) onTellMessage.accept(sender, msg);
                return;
            }
        }
        if (onChatMessage != null) {
            onChatMessage.accept(null, text);
        }
    }

    // ==================== KeepAlive ====================

    private void sendKeepAlivePacket(int packetId, long id) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(buf);
            d.writeLong(id);
            d.flush();
            sendPacketRaw(packetId, buf.toByteArray());
            out.flush();
        } catch (IOException e) {
            log.debug("KeepAlive 失败: " + e.getMessage());
        }
    }

    // ==================== 底层 IO（含压缩支持）====================

    /**
     * 校验对端声明的长度，超限直接拒绝而不是去分配内存。
     *
     * @param length 对端给的字节数
     * @param what   字段名（用于日志）
     * @throws IOException 长度非法或超过 {@link #MAX_PACKET_SIZE}
     */
    private static void checkPacketLength(int length, String what) throws IOException {
        if (length < 0) {
            throw new IOException("非法包长度: " + what + "=" + length);
        }
        if (length > MAX_PACKET_SIZE) {
            throw new IOException("包长度超过上限: " + what + "=" + length
                    + " > " + MAX_PACKET_SIZE);
        }
    }

    /**
     * 读取一个数据包（自动处理压缩）。
     * @return Object[]{packetId, data}
     */
    private Object[] readPacket() throws IOException {
        int packetLen = VarInt.readVarInt(in);
        // 长度是对端给的数字，分配内存前必须先校验（见 MAX_PACKET_SIZE）
        checkPacketLength(packetLen, "packetLen");

        if (compressionThreshold < 0) {
            // 无压缩
            int packetId = VarInt.readVarInt(in);
            int dataLen = packetLen - VarInt.writeVarInt(packetId).length;
            if (dataLen < 0) dataLen = 0;
            byte[] data = new byte[dataLen];
            in.readFully(data);
            return new Object[]{packetId, data};
        }

        // 有压缩
        int dataLen = VarInt.readVarInt(in);  // 未压缩数据长度
        // 这个数字同样是对端给的，而且会被直接当成 new byte[] 的长度
        checkPacketLength(dataLen, "uncompressedLen");
        int remaining = packetLen - VarInt.writeVarInt(dataLen).length;
        if (remaining < 0) {
            throw new IOException("压缩包长度非法: packetLen=" + packetLen + " dataLen=" + dataLen);
        }

        if (dataLen == 0) {
            // 未压缩（数据小于 threshold）
            int packetId = VarInt.readVarInt(in);
            int payloadLen = remaining - VarInt.writeVarInt(packetId).length;
            if (payloadLen < 0) payloadLen = 0;
            byte[] data = new byte[payloadLen];
            in.readFully(data);
            return new Object[]{packetId, data};
        }

        // 压缩数据
        byte[] compressed = new byte[remaining];
        in.readFully(compressed);

        byte[] decompressed = new byte[dataLen];
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        int actualLen;
        try {
            // inflate 一次不保证填满目标缓冲（zlib 可能只吐一部分），必须循环到
            // finished / 缓冲区满为止，否则会把一个完整包读成半截。
            actualLen = 0;
            while (!inflater.finished() && actualLen < decompressed.length) {
                int n = inflater.inflate(decompressed, actualLen, decompressed.length - actualLen);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new IOException("压缩包数据不完整（已解出 " + actualLen + "/" + dataLen + " 字节）");
                    }
                }
                actualLen += n;
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("解压失败", e);
        } finally {
            // 必须放在 finally：上面既可能抛 DataFormatException，也可能抛 IOException
            // （数据不完整），原实现只在 DataFormatException 分支里 end()，
            // 坏包会漏掉 Inflater 的本地内存（zlib 的 native 资源不受 GC 管理）
            inflater.end();
        }

        ByteArrayInputStream bais = new ByteArrayInputStream(decompressed, 0, actualLen);
        DataInputStream din = new DataInputStream(bais);
        int packetId = VarInt.readVarInt(din);
        int payloadLen = actualLen - VarInt.writeVarInt(packetId).length;
        if (payloadLen < 0) {
            throw new IOException("解压后长度不足以容纳包 ID: actualLen=" + actualLen + " packetId=" + packetId);
        }
        byte[] data = new byte[payloadLen];
        din.readFully(data);
        return new Object[]{packetId, data};
    }

    /**
     * 发送数据包（自动处理压缩）。
     * synchronized：保持心跳/聊天等不同线程对同一数据流的写入原子性，防止数据包交错损坏协议流。
     */
    private synchronized void sendPacketRaw(int packetId, byte[] data) throws IOException {
        // 组装 packetId + data
        ByteArrayOutputStream full = new ByteArrayOutputStream();
        full.write(VarInt.writeVarInt(packetId));
        full.write(data);
        byte[] packet = full.toByteArray();

        if (compressionThreshold < 0) {
            // 无压缩
            ByteArrayOutputStream framed = new ByteArrayOutputStream();
            framed.write(VarInt.writeVarInt(packet.length));
            framed.write(packet);
            out.write(framed.toByteArray());
        } else if (packet.length < compressionThreshold) {
            // 未压缩（数据小于 threshold）
            ByteArrayOutputStream framed = new ByteArrayOutputStream();
            framed.write(VarInt.writeVarInt(packet.length + 1));  // +1 for dataLen=0
            framed.write(VarInt.writeVarInt(0));  // dataLen = 0
            framed.write(packet);
            out.write(framed.toByteArray());
        } else {
            // 压缩
            Deflater deflater = new Deflater();
            deflater.setInput(packet);
            deflater.finish();
            byte[] compressed = new byte[packet.length * 2 + 256];
            int compressedLen = deflater.deflate(compressed);
            deflater.end();

            byte[] compressedData = new byte[compressedLen];
            System.arraycopy(compressed, 0, compressedData, 0, compressedLen);

            ByteArrayOutputStream framed = new ByteArrayOutputStream();
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            inner.write(VarInt.writeVarInt(packet.length));  // dataLen = 未压缩长度
            inner.write(compressedData);
            byte[] innerBytes = inner.toByteArray();

            framed.write(VarInt.writeVarInt(innerBytes.length));
            framed.write(innerBytes);
            out.write(framed.toByteArray());
        }
        out.flush();
    }

    private String readString(DataInputStream d) throws IOException {
        int len = VarInt.readVarInt(d);
        byte[] bytes = new byte[len];
        d.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ==================== JSON 聊天解析 ====================

    public static String parseChatJson(String json) {
        if (json == null || json.isEmpty()) return "";
        try {
            return parseChatJsonElement(JsonParser.parseString(json));
        } catch (Exception e) {
            return stripColor(json);
        }
    }

    private static String parseChatJsonElement(JsonElement el) {
        if (el == null) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            StringBuilder sb = new StringBuilder();

            // text 字段
            if (obj.has("text")) {
                JsonElement textEl = obj.get("text");
                if (textEl.isJsonPrimitive()) sb.append(textEl.getAsString());
                else sb.append(parseChatJsonElement(textEl));
            }

            // translate 字段
            if (obj.has("translate")) {
                String tr = obj.get("translate").getAsString();

                if ("commands.message.display.incoming".equals(tr)) {
                    JsonElement withEl = obj.get("with");
                    if (withEl != null && withEl.isJsonArray()) {
                        JsonArray with = withEl.getAsJsonArray();
                        if (with.size() >= 2)
                            return parseChatJsonElement(with.get(0))
                                    + " whispers to you: " + parseChatJsonElement(with.get(1));
                    }
                }

                // with 字段 — 可能是数组或字符串
                if (obj.has("with")) {
                    JsonElement withEl = obj.get("with");
                    if (withEl.isJsonArray()) {
                        StringBuilder ws = new StringBuilder();
                        for (JsonElement w : withEl.getAsJsonArray()) ws.append(parseChatJsonElement(w));
                        if (sb.length() > 0 && ws.length() > 0) sb.append(": ");
                        sb.append(ws);
                    } else if (withEl.isJsonPrimitive()) {
                        String ws = withEl.getAsString();
                        if (sb.length() > 0 && !ws.isEmpty()) sb.append(": ");
                        sb.append(ws);
                    } else {
                        String ws = parseChatJsonElement(withEl);
                        if (sb.length() > 0 && !ws.isEmpty()) sb.append(": ");
                        sb.append(ws);
                    }
                }

                // extra 字段
                if (obj.has("extra"))
                    for (JsonElement e : obj.getAsJsonArray("extra")) sb.append(parseChatJsonElement(e));

                return sb.toString();
            }

            // extra 字段（无 translate）
            if (obj.has("extra"))
                for (JsonElement e : obj.getAsJsonArray("extra")) sb.append(parseChatJsonElement(e));

            // with 字段（无 translate）
            if (obj.has("with")) {
                JsonElement withEl = obj.get("with");
                if (withEl.isJsonArray())
                    for (JsonElement e : withEl.getAsJsonArray()) sb.append(parseChatJsonElement(e));
                else if (withEl.isJsonPrimitive())
                    sb.append(withEl.getAsString());
                else
                    sb.append(parseChatJsonElement(withEl));
            }

            // fallback: 尝试收集所有字符串值
            if (sb.length() == 0) {
                for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                    JsonElement v = entry.getValue();
                    if (v.isJsonPrimitive()) sb.append(v.getAsString());
                    else if (v.isJsonObject()) sb.append(parseChatJsonElement(v));
                    else if (v.isJsonArray())
                        for (JsonElement e : v.getAsJsonArray()) sb.append(parseChatJsonElement(e));
                }
            }

            return sb.toString();
        }
        if (el.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : el.getAsJsonArray()) sb.append(parseChatJsonElement(e));
            return sb.toString();
        }
        return "";
    }

    public static String stripColor(String text) {
        return text == null ? "" : text.replaceAll("§[0-9a-fk-or]", "");
    }
}
