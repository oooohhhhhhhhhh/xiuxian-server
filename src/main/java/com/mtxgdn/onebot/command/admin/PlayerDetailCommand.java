package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;

public class PlayerDetailCommand extends Command {
    public PlayerDetailCommand() {
        super(new String[]{"玩家详情", "playerinfo", "playerdetail"},
                "查看指定玩家详情（仅私聊）",
                "/玩家详情 <玩家ID或角色名>",
                "管理", "admin.status", true);
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = new QqBindingService().findByQq(ctx.getSenderId());
        if (b == null) {
            ctx.reply("请先绑定账号。");
            return;
        }
        if (!PermissionService.hasPermission(b.getUserId(), "admin.status")) {
            ctx.reply("权限不足，你无权使用此功能。");
            return;
        }

        String arg = ctx.getArg();
        if (arg == null || arg.isBlank()) {
            ctx.reply("用法: /玩家详情 <玩家ID或角色名>");
            return;
        }

        PlayerService playerService = ServiceRegistry.getPlayerService();
        ItemService itemService = ServiceRegistry.getItemService();
        Player p;

        try {
            long playerId = Long.parseLong(arg.trim());
            p = playerService.getPlayerById(playerId);
        } catch (NumberFormatException e) {
            var players = playerService.searchPlayersByName(arg.trim(), 1, 0);
            if (players.isEmpty()) {
                ctx.reply("未找到玩家: " + arg);
                return;
            }
            PlayerInfo found = players.get(0);
            p = playerService.getPlayerById(found.getId());
        }

        if (p == null) {
            ctx.reply("未找到玩家: " + arg);
            return;
        }

        var root = p.getSpiritualRoot();
        long ss = itemService.getSpiritStoneCount(p.getId());

        StringBuilder sb = new StringBuilder();
        sb.append("===== 玩家详情 =====\n");
        sb.append("ID: ").append(p.getId()).append("\n");
        sb.append("用户ID: ").append(p.getUserId()).append("\n");
        sb.append("名称: ").append(p.getName()).append("\n");
        sb.append("灵根: ");
        if (root != null) {
            sb.append(root.getDisplayName()).append(" ").append(root.getTier().getDisplayName());
        } else {
            sb.append("无");
        }
        sb.append("\n");
        sb.append("境界: Lv.").append(p.getRealm()).append(" (Lv.").append(p.getLevel()).append(")\n");
        sb.append("灵力: ").append(p.getExperience()).append("\n");
        sb.append("生命: ").append(p.getHp()).append("/").append(p.getMaxHp()).append("\n");
        sb.append("法力: ").append(p.getMp()).append("/").append(p.getMaxMp()).append("\n");
        sb.append("攻击: ").append(p.getAttack()).append("\n");
        sb.append("防御: ").append(p.getDefense()).append("\n");
        sb.append("速度: ").append(p.getSpeed()).append("\n");
        sb.append("神识: ").append(p.getSpirit()).append("\n");
        sb.append("金币: ").append(p.getGold()).append("\n");
        sb.append("灵石: ").append(ss).append("\n");
        sb.append("修炼中: ").append(p.isCultivating() ? "是" : "否").append("\n");
        sb.append("修炼进度: ").append(p.getCultivationProgress()).append("\n");
        sb.append("=====================");
        ctx.reply(sb.toString());
    }
}
