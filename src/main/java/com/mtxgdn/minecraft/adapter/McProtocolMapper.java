package com.mtxgdn.minecraft.adapter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.mtxgdn.minecraft.VarInt;
import com.mtxgdn.util.GameLogger;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minecraft 协议版本映射器。
 * <p>
 * 根据协议版本号返回对应的包 ID，支持 1.17.x ~ 26.2。
 * 同时提供 Status Ping 自动检测服务器协议版本的功能。
 * <p>
 * <b>26.2（协议 776）</b>的包号与字段布局核对自官方未混淆服务端 {@code server-26.2.jar}：
 * {@code net/minecraft/network/protocol/game/GameProtocols}（PLAY 双向）、
 * {@code configuration/ConfigurationProtocols}（配置阶段）、{@code login/LoginProtocols}（登录阶段）。
 * 包 ID 就是 builder 链上 {@code addPacket} 的序号，客户端方向 0x00 被 bundle 包占用。
 */
public class McProtocolMapper {

    private static final GameLogger log = GameLogger.getLogger("McProto");

    public final int protocol;
    public final String versionLabel;

    // ---- 版本特性判定 ----
    public final boolean isLegacyChat;        // < 1.19（旧版聊天格式）
    public final boolean hasPlayerChat;        // >= 1.19（独立的 Player Chat 包）
    public final boolean hasDisguisedChat;     // >= 1.19.2（Disguised Chat 包）
    public final boolean hasConfigPhase;      // >= 1.20（登录后需经过配置阶段）
    public final boolean hasLoginAck;         // >= 1.20（登录成功后需发送 Login Acknowledged）
    public final boolean hasLoginUuid;        // >= 1.20.5（Login Start 包含玩家 UUID）
    public final boolean hasKnownPacks;       // >= 1.20.5（配置阶段 select_known_packs）

    // ---- 26.2（协议 776）字段布局变化 ----
    /** >= 765（1.20.3）：聊天组件在网络上走 NBT，不再是 JSON 字符串。 */
    public final boolean componentsAreNbt;
    /** >= 776：{@code ServerboundChatCommandPacket} 只有一个命令字符串（签名版另拆到 0x08）。 */
    public final boolean plainChatCommand;
    /** >= 776：{@code LastSeenMessages.Update} = VarInt 偏移 + 20 位确认位图（3 字节）+ 校验字节。 */
    public final boolean chatAckV2;

    // ---- Play 阶段：服务端→客户端 ----
    public final int s2cKeepAlive;
    public final int s2cSystemChat;
    public final int s2cDisguisedChat;
    public final int s2cPlayerChat;
    public final int s2cDisconnect;

    // ---- Play 阶段：客户端→服务端 ----
    public final int c2sKeepAlive;
    public final int c2sChatMessage;
    public final int c2sChatCommand;

    // ---- 配置阶段：1.20+ ----
    public final int s2cConfigFinish;         // 服务端：配置完成 (Finish Configuration)
    public final int c2sConfigFinish;         // 客户端：配置完成确认 (Ack Finish Configuration)
    public final int s2cConfigKeepAlive;
    public final int c2sConfigKeepAlive;
    public final int c2sClientInfo;           // 客户端信息（locale, view distance, chat mode 等）
    public final int s2cConfigKnownPacks;     // 服务端 Known Packs（1.20.5+）
    public final int c2sConfigKnownPacks;     // 客户端 Known Packs（1.20.5+）
    public final int s2cConfigRegistryData;   // 服务端：同步注册表（1.20.5+；聊天类型 id 映射从这里来）
    public final int s2cConfigDisconnect;     // 服务端：配置阶段踢出（1.20.5+）
    public final int s2cConfigPing;           // 服务端：延迟探测（1.20.5+）
    public final int c2sConfigPong;           // 客户端：延迟回包（1.20.5+）
    public final int s2cConfigResourcePackPush;  // 服务端：要求加载资源包（1.20.5+）
    public final int c2sConfigResourcePack;      // 客户端：资源包处理结果（1.20.5+）
    public final int s2cConfigCodeOfConduct;     // 服务端：行为准则（26.2 新增）
    public final int c2sConfigAcceptCodeOfConduct; // 客户端：接受行为准则（26.2 新增）

    // ---- Play 阶段：26.2 新增用到的包 ----
    public final int s2cPing;                 // 服务端：延迟探测（Play）
    public final int c2sPong;                 // 客户端：延迟回包（Play）
    public final int s2cStartConfiguration;   // 服务端：要求重新进入配置阶段（/reload 等）
    public final int c2sConfigAcknowledged;   // 客户端：确认重新配置
    public final int s2cRespawn;              // 服务端：重生
    public final int c2sPlayerLoaded;         // 客户端：客户端已加载（重生后必须回，否则服务端一直认为玩家没加载完）

    // ---- 登录阶段：客户端→服务端 ----
    public final int c2sLoginAck;             // Login Acknowledged（>= 1.20，登录成功后转入配置阶段）

    // ---- 登录阶段常量 ----
    public static final int LOGIN_START = 0x00;
    public static final int LOGIN_SUCCESS = 0x02;
    public static final int LOGIN_DISCONNECT = 0x00;
    public static final int LOGIN_ACK = 0x03;
    public static final int HANDSHAKE = 0x00;

    private McProtocolMapper(int proto) {
        this.protocol = proto;
        this.versionLabel = labelOf(proto);

        // 版本区间判定
        this.isLegacyChat     = proto < 759;      // < 1.19
        this.hasPlayerChat    = proto >= 759;     // >= 1.19
        this.hasDisguisedChat = proto >= 761;     // >= 1.19.2
        this.hasConfigPhase   = proto >= 764;     // >= 1.20
        this.hasLoginAck      = proto >= 764;     // >= 1.20
        this.hasLoginUuid     = proto >= 766;     // >= 1.20.5
        this.hasKnownPacks    = proto >= 766;     // >= 1.20.5

        // ---- 字段布局变化（26.2 核对自 server-26.2.jar）----
        this.componentsAreNbt = proto >= 765;     // 1.20.3 起组件走 NBT
        this.plainChatCommand = proto >= 776;     // 26.2：命令包只剩字符串
        this.chatAckV2        = proto >= 776;     // 26.2：偏移 + 20 位位图 + 校验字节

        // ---- Play 阶段包 ID ----
        if (proto < 759) {
            // 1.17 ~ 1.18.2（旧版聊天）
            s2cKeepAlive     = 0x21;
            s2cSystemChat    = 0x0F;   // 旧版 Chat Message
            s2cDisguisedChat = -1;
            s2cPlayerChat    = -1;
            s2cDisconnect    = 0x1A;
            c2sKeepAlive     = 0x0F;
            c2sChatMessage   = 0x03;
            c2sChatCommand   = -1;
        } else if (proto == 759) {
            // 1.19
            s2cKeepAlive     = 0x1E;
            s2cSystemChat    = 0x5F;
            s2cDisguisedChat = -1;
            s2cPlayerChat    = 0x30;
            s2cDisconnect    = 0x17;
            c2sKeepAlive     = 0x11;
            c2sChatMessage   = 0x04;
            c2sChatCommand   = 0x03;
        } else if (proto == 760) {
            // 1.19.1
            s2cKeepAlive     = 0x20;
            s2cSystemChat    = 0x62;
            s2cDisguisedChat = -1;
            s2cPlayerChat    = 0x32;
            s2cDisconnect    = 0x19;
            c2sKeepAlive     = 0x12;
            c2sChatMessage   = 0x05;
            c2sChatCommand   = 0x04;
        } else if (proto == 761) {
            // 1.19.2
            s2cKeepAlive     = 0x1F;
            s2cSystemChat    = 0x60;
            s2cDisguisedChat = 0x18;
            s2cPlayerChat    = 0x31;
            s2cDisconnect    = 0x17;
            c2sKeepAlive     = 0x11;
            c2sChatMessage   = 0x05;
            c2sChatCommand   = 0x04;
        } else if (proto == 762 || proto == 763) {
            // 1.19.3 ~ 1.19.4（共享同一组包 ID）
            s2cKeepAlive     = 0x23;
            s2cSystemChat    = 0x64;
            s2cDisguisedChat = 0x1B;
            s2cPlayerChat    = 0x35;
            s2cDisconnect    = 0x1A;
            c2sKeepAlive     = 0x12;
            c2sChatMessage   = 0x05;
            c2sChatCommand   = 0x04;
        } else if (proto == 764) {
            // 1.20 ~ 1.20.1
            s2cKeepAlive     = 0x24;
            s2cSystemChat    = 0x67;
            s2cDisguisedChat = 0x1C;
            s2cPlayerChat    = 0x37;
            s2cDisconnect    = 0x1B;
            c2sKeepAlive     = 0x14;
            c2sChatMessage   = 0x05;
            c2sChatCommand   = 0x04;
        } else if (proto == 765) {
            // 1.20.2
            s2cKeepAlive     = 0x24;
            s2cSystemChat    = 0x69;
            s2cDisguisedChat = 0x1C;
            s2cPlayerChat    = 0x37;
            s2cDisconnect    = 0x1B;
            c2sKeepAlive     = 0x15;
            c2sChatMessage   = 0x05;
            c2sChatCommand   = 0x04;
        } else if (proto == 766 || proto == 767) {
            // 1.20.5 ~ 1.20.6
            s2cKeepAlive     = 0x26;
            s2cSystemChat    = 0x6C;
            s2cDisguisedChat = 0x1E;
            s2cPlayerChat    = 0x39;
            s2cDisconnect    = 0x1D;
            c2sKeepAlive     = 0x18;
            c2sChatMessage   = 0x06;
            c2sChatCommand   = 0x04;
        } else if (proto == 768 || proto == 769) {
            // 1.21 ~ 1.21.1
            s2cKeepAlive     = 0x27;
            s2cSystemChat    = 0x73;
            s2cDisguisedChat = 0x1E;
            s2cPlayerChat    = 0x3B;
            s2cDisconnect    = 0x1D;
            c2sKeepAlive     = 0x1A;
            c2sChatMessage   = 0x07;
            c2sChatCommand   = 0x05;
        } else if (proto >= 770 && proto <= 772) {
            // 1.21.2 ~ 1.21.3
            s2cKeepAlive     = 0x26;
            s2cSystemChat    = 0x72;
            s2cDisguisedChat = 0x1D;
            s2cPlayerChat    = 0x3A;
            s2cDisconnect    = 0x1C;
            c2sKeepAlive     = 0x1A;
            c2sChatMessage   = 0x07;
            c2sChatCommand   = 0x05;
        } else if (proto == 773 || proto == 774) {
            // 1.21.4 ~ 1.21.5
            s2cKeepAlive     = 0x2B;
            s2cSystemChat    = 0x77;
            s2cDisguisedChat = 0x21;
            s2cPlayerChat    = 0x3F;
            s2cDisconnect    = 0x20;
            c2sKeepAlive     = 0x1B;
            c2sChatMessage   = 0x08;
            c2sChatCommand   = 0x06;
        } else if (proto >= 776) {
            // 26.2（协议 776）。包号与 1.21.6+ 相同，但 ServerboundChat / ChatCommand /
            // LastSeenMessages.Update 的字段布局变了，由 plainChatCommand / chatAckV2 控制。
            s2cKeepAlive     = 0x2C;
            s2cSystemChat    = 0x79;
            s2cDisguisedChat = 0x21;
            s2cPlayerChat    = 0x41;
            s2cDisconnect    = 0x20;
            c2sKeepAlive     = 0x1C;
            c2sChatMessage   = 0x09;
            c2sChatCommand   = 0x07;
        } else {
            // proto >= 775，1.21.6+
            s2cKeepAlive     = 0x2C;
            s2cSystemChat    = 0x79;
            s2cDisguisedChat = 0x21;
            s2cPlayerChat    = 0x41;
            s2cDisconnect    = 0x20;
            c2sKeepAlive     = 0x1C;
            c2sChatMessage   = 0x09;
            c2sChatCommand   = 0x07;
        }

        // ---- 26.2 才有的 Play 阶段包（旧协议保持 -1，即「不支持」）----
        if (proto >= 776) {
            s2cPing               = 0x3D;
            c2sPong               = 0x2D;
            s2cStartConfiguration = 0x76;
            c2sConfigAcknowledged = 0x10;
            s2cRespawn            = 0x52;
            c2sPlayerLoaded       = 0x2C;
        } else {
            s2cPing               = -1;
            c2sPong               = -1;
            s2cStartConfiguration = -1;
            c2sConfigAcknowledged = -1;
            s2cRespawn            = -1;
            c2sPlayerLoaded       = -1;
        }

        // ---- 配置阶段包 ID ----
        if (proto < 764) {
            // 不适用配置阶段
            s2cConfigFinish     = -1;
            c2sConfigFinish     = -1;
            s2cConfigKeepAlive  = -1;
            c2sConfigKeepAlive  = -1;
            c2sClientInfo       = -1;
            s2cConfigKnownPacks = -1;
            c2sConfigKnownPacks = -1;
            s2cConfigRegistryData = -1;
            s2cConfigDisconnect = -1;
            s2cConfigPing = -1;
            c2sConfigPong = -1;
            s2cConfigResourcePackPush = -1;
            c2sConfigResourcePack = -1;
        } else if (proto <= 765) {
            // 1.20 ~ 1.20.2（无 select_known_packs，无 cookie_response）
            s2cConfigFinish     = 0x03;
            c2sConfigFinish     = 0x02;
            s2cConfigKeepAlive  = 0x04;
            c2sConfigKeepAlive  = 0x03;
            c2sClientInfo       = 0x00;
            s2cConfigKnownPacks = -1;
            c2sConfigKnownPacks = -1;
            // 本分支的包号未逐项核对（手上只有 26.2 的服务端），新增的这几个包一律标为不支持，
            // 避免填一个猜出来的号把 1.20~1.20.2 的连接搞坏。
            s2cConfigRegistryData = -1;
            s2cConfigDisconnect = -1;
            s2cConfigPing = -1;
            c2sConfigPong = -1;
            s2cConfigResourcePackPush = -1;
            c2sConfigResourcePack = -1;
        } else {
            // 1.20.5+（含 select_known_packs 和 cookie_response）；26.2 已逐项核对
            s2cConfigFinish     = 0x03;
            c2sConfigFinish     = 0x03;
            s2cConfigKeepAlive  = 0x04;
            c2sConfigKeepAlive  = 0x04;
            c2sClientInfo       = 0x00;
            s2cConfigKnownPacks = 0x0E;
            c2sConfigKnownPacks = 0x07;
            s2cConfigRegistryData = 0x07;
            s2cConfigDisconnect = 0x02;
            s2cConfigPing = 0x05;
            c2sConfigPong = 0x05;
            s2cConfigResourcePackPush = 0x09;
            c2sConfigResourcePack = 0x06;
        }

        // ---- 26.2 新增的配置阶段包：行为准则 ----
        if (proto >= 776) {
            s2cConfigCodeOfConduct = 0x13;
            c2sConfigAcceptCodeOfConduct = 0x09;
        } else {
            s2cConfigCodeOfConduct = -1;
            c2sConfigAcceptCodeOfConduct = -1;
        }

        // ---- Login Acknowledged（>= 1.20，登录成功后转入配置阶段） ----
        this.c2sLoginAck = (proto >= 764) ? 0x03 : -1;
    }

    // ---- 工厂方法 ----

    public static McProtocolMapper of(int protocol) {
        return new McProtocolMapper(protocol);
    }

    /**
     * 通过 Status Ping 自动检测服务器协议版本。
     * @return McProtocolMapper，若无法检测则返回 null
     */
    public static McProtocolMapper detect(String host, int port, int fallbackProtocol) {
        try (Socket s = new Socket()) {
            s.setSoTimeout(5000);
            s.connect(new InetSocketAddress(host, port), 5000);

            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());

            // Handshake (nextState=1: status)
            ByteArrayOutputStream hsb = new ByteArrayOutputStream();
            DataOutputStream hOut = new DataOutputStream(hsb);
            VarInt.writeVarInt(fallbackProtocol, hOut);
            VarInt.writeString(hOut, host);
            // 端口必须用无符号短整型写入
            hOut.write((port >> 8) & 0xFF);
            hOut.write(port & 0xFF);
            VarInt.writeVarInt(1, hOut);
            hOut.flush();
            sendRawPacket(out, 0x00, hsb.toByteArray());

            // Status Request
            sendRawPacket(out, 0x00, new byte[0]);
            out.flush();

            // Read Status Response
            int len = VarInt.readVarInt(in);
            log.debug("Status Response 包长度: " + len);
            
            int pid = VarInt.readVarInt(in);
            log.debug("Status Response 包ID: " + pid);
            
            if (pid == 0x00) {
                // Status Response 中 JSON 是 VarInt 长度前缀的字符串
                int jsonLen = VarInt.readVarInt(in);
                log.debug("JSON 长度: " + jsonLen);
                
                byte[] jsonBytes = new byte[jsonLen];
                in.readFully(jsonBytes);
                String json = new String(jsonBytes, StandardCharsets.UTF_8);
                log.debug("JSON 内容: " + (json.length() > 100 ? json.substring(0, 100) + "..." : json));

                JsonReader reader = new JsonReader(new StringReader(json));
                reader.setStrictness(com.google.gson.Strictness.LENIENT);
                JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                if (obj.has("version")) {
                    JsonObject ver = obj.getAsJsonObject("version");
                    int detectedProto = ver.get("protocol").getAsInt();
                    String name = ver.get("name").getAsString();
                    log.info("服务器版本: " + name + " (协议 " + detectedProto + ")");
                    // 直接用状态查询报出来的协议号。它就是服务端当前接受的版本号
                    // （装了 ViaVersion 时也是它对外宣称的那个），任何「按名字往上抬」的修正
                    // 都只会在握手阶段发错字段布局：例如 1.19.4 的服务端被抬到 1.20.5 之后，
                    // Login Start 会多带一个 UUID 字段，直接解析失败。
                    return new McProtocolMapper(detectedProto);
                }
            }
        } catch (Exception e) {
            log.debug("Status Ping 失败: " + e.getMessage());
        }
        return null;
    }

    static void sendRawPacket(DataOutputStream out, int packetId, byte[] data) throws IOException {
        ByteArrayOutputStream fullBuf = new ByteArrayOutputStream();
        fullBuf.write(VarInt.writeVarInt(packetId));
        fullBuf.write(data);
        byte[] packetData = fullBuf.toByteArray();

        ByteArrayOutputStream finalBuf = new ByteArrayOutputStream();
        finalBuf.write(VarInt.writeVarInt(packetData.length));
        finalBuf.write(packetData);
        out.write(finalBuf.toByteArray());
        out.flush();
    }

    private static String labelOf(int proto) {
        if (proto >= 776) return "26.2+";
        if (proto >= 775) return "1.21.6+";
        if (proto >= 773) return "1.21.4-1.21.5";
        if (proto >= 771) return "1.21.2-1.21.3";
        if (proto >= 768) return "1.21-1.21.1";
        if (proto >= 766) return "1.20.5-1.20.6";
        if (proto >= 764) return "1.20-1.20.4";
        if (proto == 763) return "1.19.4";
        if (proto == 762) return "1.19.3";
        if (proto == 761) return "1.19.2";
        if (proto == 760) return "1.19.1";
        if (proto == 759) return "1.19";
        if (proto >= 757) return "1.18-1.18.2";
        if (proto >= 755) return "1.17-1.17.1";
        return "Unknown";
    }
}
