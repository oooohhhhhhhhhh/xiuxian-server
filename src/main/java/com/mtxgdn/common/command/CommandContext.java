package com.mtxgdn.common.command;

import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.minecraft.adapter.MinecraftPlayerBinding;
import com.mtxgdn.minecraft.adapter.MinecraftPlayerBindingService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.i18n.GameLanguage;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.i18n.I18nManager;
import com.mtxgdn.permission.PermissionService;

public abstract class CommandContext {

    /** 平台感知的绑定查找器：OneBot 查 qq_bindings，QQ 官方查 qq_official_bindings */
    @FunctionalInterface
    public interface QqBindingLookup {
        QqBinding lookup(String id);
    }

    private final String senderId;
    private final String senderNickname;
    private final String arg;
    private final QqBindingLookup qqBindingLookup;

    protected CommandContext(String senderId, String senderNickname, String arg) {
        this(senderId, senderNickname, arg, null);
    }

    protected CommandContext(String senderId, String senderNickname, String arg, QqBindingLookup qqBindingLookup) {
        this.senderId = senderId;
        this.senderNickname = senderNickname;
        this.arg = arg;
        this.qqBindingLookup = qqBindingLookup;
    }

    public String getSenderId() {
        return senderId;
    }

    public String getSenderNickname() {
        return senderNickname;
    }

    public String getArg() {
        return arg;
    }

    public abstract boolean isGroup();

    public abstract void reply(String message);

    public abstract void replyPrivate(String message);

    /** 解析 QQ 绑定：优先使用平台查找器（如 QQ 官方 openid），否则回退 OneBot qq_bindings */
    private QqBinding resolveQqBinding() {
        if (qqBindingLookup != null) {
            QqBinding b = qqBindingLookup.lookup(senderId);
            if (b != null) return b;
        }
        return new QqBindingService().findByQq(senderId);
    }

    /**
     * 平台感知地解析当前发送者的绑定（OneBot qq_bindings / QQ官方 qq_official_bindings）。
     * 供指令内自行校验操作者绑定时使用，避免硬编码查 OneBot 绑定表导致 QQ官方用户被误判未绑定。
     */
    public QqBinding resolveBoundBinding() {
        return resolveQqBinding();
    }

    /** 平台感知地解析绑定用户 ID（不回复提示）；未绑定返回 null。供 /帮助 等免登录指令使用 */
    public Long resolveBoundUserId() {
        QqBinding qqB = resolveQqBinding();
        if (qqB != null) return qqB.getUserId();
        MinecraftPlayerBinding mcB = ServiceRegistry.getMcBindingService().findByMcUuid(senderId);
        if (mcB != null) return mcB.getUserId();
        return null;
    }

    public Long requireBinding() {
        Long userId = resolveBoundUserId();
        if (userId != null) {
            return userId;
        }
        // 按发送者语言回复：未绑定账号时只能拿到通道默认语言（服务端默认），
        // 这已是无偏好可用时的最优解。
        reply(I18n.t("game.bind_required", lang()));
        return null;
    }

    public PlayerInfo requirePlayer(Long userId) {
        PlayerInfo player = ServiceRegistry.getPlayerService().getPlayerByUserId(userId);
        if (player == null) {
            reply(I18n.t("game.no_player", lang()));
            return null;
        }
        return player;
    }

    public boolean checkPermission(String permission) {
        Long userId = resolveUserId();
        if (userId == null) {
            return false;
        }
        if (!PermissionService.hasPermission(userId, permission)) {
            reply(I18n.t("command_context.insufficient_permission"));
            return false;
        }
        return true;
    }

    public boolean requirePermission(String permission) {
        Long userId = resolveUserId();
        if (userId == null) {
            reply(I18n.t("command_context.please_bind_your_account_first"));
            return false;
        }
        if (!PermissionService.hasPermission(userId, permission)) {
            reply(I18n.t("command_context.insufficient_permission"));
            return false;
        }
        return true;
    }

    private Long resolveUserId() {
        QqBinding qqB = resolveQqBinding();
        if (qqB != null) return qqB.getUserId();
        MinecraftPlayerBinding mcB = ServiceRegistry.getMcBindingService().findByMcUuid(senderId);
        if (mcB != null) return mcB.getUserId();
        return null;
    }

    /**
     * 本条指令回复时应使用的语言。
     *
     * <p>子类按平台覆写：已绑定玩家取其账号语言，未绑定访客回落服务端默认。
     * 解析过程会查库（绑定表 + players），因此<b>任何异常都只能吞掉</b>并回落默认语言
     * ——语言偏好丢了顶多是回复变成中文，让指令本身报错是不可接受的。</p>
     */
    public GameLanguage lang() {
        return I18nManager.serverDefault();
    }
    public static String formatPlayerStatus(PlayerInfo p) {
        var root = p.getSpiritualRoot();
        String rootStr = root != null
                ? "【" + root.getDisplayName() + "】" + root.getTier().getDisplayName()
                        + " | " + root.getDescription()
                : "无";
        ItemService itemService = ServiceRegistry.getItemService();
        return "【" + p.getName() + "】" +
                "\n灵根: " + rootStr +
                "\n境界: " + p.getRealmName() + " (Lv." + p.getLevel() + ")" +
                "\n经验: " + p.getExperience() +
                "\n生命: " + p.getHp() + "/" + p.getMaxHp() +
                "  法力: " + p.getMp() + "/" + p.getMaxMp() +
                "\n攻击: " + p.getAttack() +
                "  防御: " + p.getDefense() +
                "  速度: " + p.getSpeed() +
                "  神识: " + p.getSpirit() +
                "\n金币: " + p.getGold() +
                "  灵石: " + itemService.getSpiritStoneCount(p.getId()) +
                (p.isCultivating() ? "\n状态: 闭关中" : "");
    }

    public static String realmName(int realmId) {
        var cfg = GameConfigLoader.getRealmConfig(realmId, 0);
        return cfg != null ? cfg.getName() : "境界" + realmId;
    }

    public static String itemName(String itemKey) {
        Item item = ItemRegistry.resolve(itemKey);
        return item != null ? item.getName() : itemKey;
    }
}
