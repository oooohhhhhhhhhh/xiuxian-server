package com.mtxgdn.onebot.command.social;

import com.mtxgdn.common.result.OperationResult;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.Friend;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.FriendService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.List;
import java.util.Map;
import com.mtxgdn.i18n.I18n;

/**
 * 好友管理 QQ 指令：/好友 add <玩家名> | accept <玩家名> | remove <玩家名> | list | pending
 * 此前该命令文件为空壳（0 字节），好友功能仅 REST/WebSocket 可用，此处补齐 QQ 入口。
 */
public class FriendCommand extends Command {
    public FriendCommand() {
        super(new String[]{"好友", "friend"},
                "好友管理",
                "/好友 add <玩家名> - 添加好友\n/好友 accept <玩家名> - 接受申请\n/好友 reject <玩家名> - 拒绝申请\n/好友 remove <玩家名> - 删除好友\n/好友 visit <玩家名> - 拜访好友（每日限一次，双方得灵力）\n/好友 list - 好友列表\n/好友 pending - 待处理申请",
                "社交",
                "game.friend.manage");
        
        setI18nKeys("cmd.friend.desc", "cmd.friend.usage");
    setModule("friend");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        String arg = ctx.getArg();
        if (arg == null || arg.isBlank()) {
            ctx.reply(getUsage());
            return;
        }

        String[] parts = arg.trim().split("\\s+", 2);
        String sub = parts[0];
        String param = parts.length > 1 ? parts[1] : null;

        FriendService friendService = ServiceRegistry.getFriendService();
        PlayerService playerService = ServiceRegistry.getPlayerService();

        switch (sub) {
            case "add" -> {
                if (param == null || param.isBlank()) {
                    ctx.reply(I18n.t("social.usage_7"));
                    return;
                }
                List<PlayerInfo> targets = playerService.searchPlayersByName(param.trim(), 1, 0);
                if (targets.isEmpty()) {
                    ctx.reply(I18n.t("social.player_does_not_exist", param));
                    return;
                }
                PlayerInfo target = targets.get(0);
                if (target.getId() == p.getId()) {
                    ctx.reply(I18n.t("social.you_cannot_add_yourself"));
                    return;
                }
                try {
                    friendService.sendRequest(p.getId(), target.getId());
                    ctx.reply(I18n.t("social.friend_request_sent", target.getName()));
                } catch (RuntimeException e) {
                    ctx.reply(I18n.t("social.add_failed", e.getMessage()));
                }
            }
            case "accept" -> {
                if (param == null || param.isBlank()) {
                    ctx.reply(I18n.t("social.usage_6"));
                    return;
                }
                List<PlayerInfo> targets = playerService.searchPlayersByName(param.trim(), 1, 0);
                if (targets.isEmpty()) {
                    ctx.reply(I18n.t("social.player_does_not_exist", param));
                    return;
                }
                PlayerInfo target = targets.get(0);
                if (friendService.acceptRequest(p.getId(), target.getId())) {
                    ctx.reply(I18n.t("social.you_and_are_now_friends", target.getName()));
                } else {
                    ctx.reply(I18n.t("social.accept_failed"));
                }
            }
            case "reject" -> {
                if (param == null || param.isBlank()) {
                    ctx.reply(I18n.t("social.usage_2"));
                    return;
                }
                List<PlayerInfo> targets = playerService.searchPlayersByName(param.trim(), 1, 0);
                if (targets.isEmpty()) {
                    ctx.reply(I18n.t("social.player_does_not_exist", param));
                    return;
                }
                PlayerInfo target = targets.get(0);
                if (friendService.rejectRequest(p.getId(), target.getId())) {
                    ctx.reply(I18n.t("social.rejected_the_friend_request", target.getName()));
                } else {
                    ctx.reply(I18n.t("social.reject_failed"));
                }
            }
            case "remove" -> {
                if (param == null || param.isBlank()) {
                    ctx.reply(I18n.t("social.usage_5"));
                    return;
                }
                List<PlayerInfo> targets = playerService.searchPlayersByName(param.trim(), 1, 0);
                if (targets.isEmpty()) {
                    ctx.reply(I18n.t("social.player_does_not_exist", param));
                    return;
                }
                PlayerInfo target = targets.get(0);
                if (friendService.removeFriend(p.getId(), target.getId())) {
                    ctx.reply(I18n.t("social.removed_friend", target.getName()));
                } else {
                    ctx.reply(I18n.t("social.delete_failed"));
                }
            }
            case "visit" -> {
                if (param == null || param.isBlank()) {
                    ctx.reply(I18n.t("social.usage"));
                    return;
                }
                List<PlayerInfo> targets = playerService.searchPlayersByName(param.trim(), 1, 0);
                if (targets.isEmpty()) {
                    ctx.reply(I18n.t("social.player_does_not_exist", param));
                    return;
                }
                PlayerInfo target = targets.get(0);
                OperationResult result = friendService.visitFriend(p.getId(), target.getId());
                if (result.success()) {
                    ctx.reply("✅ " + result.message());
                } else {
                    ctx.reply("❌ " + result.message());
                }
            }
            case "list" -> {
                List<Friend> friends = friendService.getFriends(p.getId());
                if (friends.isEmpty()) {
                    ctx.reply(I18n.t("social.you_have_no_friends_yet"));
                    return;
                }
                StringBuilder sb = new StringBuilder("👥 我的好友（" + friends.size() + "）\n");
                for (Friend f : friends) {
                    long friendId = f.getPlayerId() == p.getId() ? f.getFriendPlayerId() : f.getPlayerId();
                    PlayerInfo fp = playerService.getPlayerInfoById(friendId);
                    sb.append("• ").append(fp != null ? fp.getName() : friendId).append("\n");
                }
                ctx.reply(sb.toString());
            }
            case "pending" -> {
                List<Friend> pending = friendService.getPendingRequests(p.getId());
                if (pending.isEmpty()) {
                    ctx.reply(I18n.t("social.there_are_no_pending_friend"));
                    return;
                }
                StringBuilder sb = new StringBuilder("📨 待处理的好友申请\n");
                for (Friend f : pending) {
                    long requesterId = f.getPlayerId() == p.getId() ? f.getFriendPlayerId() : f.getPlayerId();
                    PlayerInfo fp = playerService.getPlayerInfoById(requesterId);
                    String name = fp != null ? fp.getName() : String.valueOf(requesterId);
                    sb.append("• ").append(name).append("\n");
                    sb.append("  接受: /好友 accept ").append(name).append("\n");
                    sb.append("  拒绝: /好友 reject ").append(name).append("\n");
                }
                ctx.reply(sb.toString());
            }
            default -> ctx.reply(getUsage());
        }
    }
}
