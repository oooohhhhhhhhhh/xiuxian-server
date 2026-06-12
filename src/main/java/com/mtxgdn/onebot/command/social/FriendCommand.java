package com.mtxgdn.onebot.command.social;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.Friend;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import java.util.List;

public class FriendCommand extends Command {
    public FriendCommand() {
        super(new String[]{"friend", "好友"},
                "好友管理：add/accept/remove/list",
                "/好友 <add|accept|remove|list> [玩家名]",
                "社交", "game.friend.manage");

        registerSub(new String[]{"add", "添加"}, this::handleAdd);
        registerSub(new String[]{"accept", "接受"}, this::handleAccept);
        registerSub(new String[]{"remove", "删除"}, this::handleRemove);
        registerSub(new String[]{"list", "列表"}, this::handleList);
    }

    @Override
    protected void onDefault(CommandContext ctx, PlayerInfo p) {
        ctx.reply("用法:\n" +
                "/好友 add <玩家名>   发送好友申请\n" +
                "/好友 accept <玩家名> 接受好友申请\n" +
                "/好友 remove <玩家名> 删除好友\n" +
                "/好友 list           查看好友列表");
    }

    private void handleAdd(CommandContext ctx, PlayerInfo p, String[] parts) {
        String targetName = parts.length > 1 ? parts[1].trim() : "";
        if (targetName.isEmpty()) { ctx.reply("用法: /好友 add <玩家名>"); return; }
        List<PlayerInfo> targets = ServiceRegistry.getPlayerService().searchPlayersByName(targetName, 1, 0);
        if (targets.isEmpty()) { ctx.reply("找不到玩家: " + targetName); return; }
        PlayerInfo target = targets.get(0);
        if (target.getId() == p.getId()) { ctx.reply("不能添加自己为好友。"); return; }
        Friend result = ServiceRegistry.getFriendService().sendRequest(p.getId(), target.getId());
        if (result == null) { ctx.reply("发送好友申请失败。"); return; }
        if ("exists".equals(result.getStatus())) { ctx.reply("已经是好友或已发送过申请。"); return; }
        ctx.reply("好友申请已发送给 " + target.getName());
    }

    private void handleAccept(CommandContext ctx, PlayerInfo p, String[] parts) {
        String targetName = parts.length > 1 ? parts[1].trim() : "";
        if (targetName.isEmpty()) { ctx.reply("用法: /好友 accept <玩家名>"); return; }
        List<PlayerInfo> targets = ServiceRegistry.getPlayerService().searchPlayersByName(targetName, 1, 0);
        if (targets.isEmpty()) { ctx.reply("找不到玩家: " + targetName); return; }
        PlayerInfo target = targets.get(0);
        boolean success = ServiceRegistry.getFriendService().acceptRequest(p.getId(), target.getId());
        if (!success) { ctx.reply("没有来自 " + target.getName() + " 的好友申请。"); return; }
        ctx.reply("已与 " + target.getName() + " 结为好友！");
    }

    private void handleRemove(CommandContext ctx, PlayerInfo p, String[] parts) {
        String targetName = parts.length > 1 ? parts[1].trim() : "";
        if (targetName.isEmpty()) { ctx.reply("用法: /好友 remove <玩家名>"); return; }
        List<PlayerInfo> targets = ServiceRegistry.getPlayerService().searchPlayersByName(targetName, 1, 0);
        if (targets.isEmpty()) { ctx.reply("找不到玩家: " + targetName); return; }
        PlayerInfo target = targets.get(0);
        boolean success = ServiceRegistry.getFriendService().removeFriend(p.getId(), target.getId());
        if (!success) { ctx.reply(target.getName() + " 不是你的好友。"); return; }
        ctx.reply("已删除好友 " + target.getName());
    }

    private void handleList(CommandContext ctx, PlayerInfo p, String[] parts) {
        List<Friend> friends = ServiceRegistry.getFriendService().getFriends(p.getId());
        if (friends.isEmpty()) { ctx.reply("你还没有好友。\n使用 /好友 add <玩家名> 发送好友申请"); return; }
        StringBuilder sb = new StringBuilder("===== 好友列表 =====\n");
        for (Friend f : friends) {
            String name = f.getFriendName() != null ? f.getFriendName() : "未知";
            String realm = f.getFriendRealm() != null ? f.getFriendRealm() : "未知";
            sb.append(name).append("  ").append(realm).append("\n");
        }
        sb.append("共 ").append(friends.size()).append(" 位好友");
        ctx.reply(sb.toString());
    }
}
