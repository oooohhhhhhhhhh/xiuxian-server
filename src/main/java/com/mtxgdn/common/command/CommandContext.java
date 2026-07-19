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
import com.mtxgdn.permission.PermissionService;

public abstract class CommandContext {

    private final String senderId;
    private final String senderNickname;
    private final String arg;

    protected CommandContext(String senderId, String senderNickname, String arg) {
        this.senderId = senderId;
        this.senderNickname = senderNickname;
        this.arg = arg;
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

    public Long requireBinding() {
        // 先查 QQ 绑定
        QqBinding qqB = new QqBindingService().findByQq(senderId);
        if (qqB != null) {
            return qqB.getUserId();
        }
        // 再查 MC 绑定
        MinecraftPlayerBinding mcB = new MinecraftPlayerBindingService().findByMcUuid(senderId);
        if (mcB != null) {
            return mcB.getUserId();
        }
        reply("请先注册或绑定账号。\n注册: /register <用户名> <密码>\n绑定: /bind");
        return null;
    }

    public PlayerInfo requirePlayer(Long userId) {
        PlayerInfo player = ServiceRegistry.getPlayerService().getPlayerByUserId(userId);
        if (player == null) {
            reply("你还没有创建修仙角色。\n请使用 /register <角色名> 注册角色");
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
            reply("权限不足，你无权使用此功能。");
            return false;
        }
        return true;
    }

    public boolean requirePermission(String permission) {
        Long userId = resolveUserId();
        if (userId == null) {
            reply("请先绑定账号。\n私聊使用 /bind");
            return false;
        }
        if (!PermissionService.hasPermission(userId, permission)) {
            reply("权限不足，你无权使用此功能。");
            return false;
        }
        return true;
    }

    private Long resolveUserId() {
        QqBinding qqB = new QqBindingService().findByQq(senderId);
        if (qqB != null) return qqB.getUserId();
        MinecraftPlayerBinding mcB = new MinecraftPlayerBindingService().findByMcUuid(senderId);
        if (mcB != null) return mcB.getUserId();
        return null;
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
