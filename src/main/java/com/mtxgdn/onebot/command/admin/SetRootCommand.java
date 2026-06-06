package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;

import java.util.Arrays;
import java.util.stream.Collectors;

public class SetRootCommand extends Command {
    public SetRootCommand() {
        super(new String[]{"修改灵根", "setroot", "设置灵根", "灵根"},
                "修改玩家的灵根（仅私聊，需要管理员权限）",
                "/修改灵根 <玩家ID> <灵根名称> 或 /修改灵根 list",
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
            ctx.reply("用法: /修改灵根 <玩家ID> <灵根名称>\n"
                    + "可用灵根: /修改灵根 list");
            return;
        }

        String[] parts = arg.trim().split("\\s+", 2);

        if (parts[0].equalsIgnoreCase("list")) {
            ctx.reply(buildRootList());
            return;
        }

        if (parts.length < 2) {
            ctx.reply("用法: /修改灵根 <玩家ID> <灵根名称>\n"
                    + "例如: /修改灵根 1 TAIYI_GOLDEN\n"
                    + "可用灵根: /修改灵根 list");
            return;
        }

        long playerId;
        try {
            playerId = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            ctx.reply("玩家ID必须为数字。");
            return;
        }

        PlayerService playerService = ServiceRegistry.getPlayerService();
        var p = playerService.getPlayerById(playerId);
        if (p == null) {
            ctx.reply("未找到玩家 ID:" + playerId);
            return;
        }

        SpiritualRoot newRoot;
        try {
            String rootName = parts[1].trim().toUpperCase();
            newRoot = SpiritualRoot.valueOf(rootName);
        } catch (IllegalArgumentException e) {
            ctx.reply("无效的灵根名称: " + parts[1] + "\n" + buildRootList());
            return;
        }

        SpiritualRoot oldRoot = p.getSpiritualRoot();
        playerService.updateSpiritualRoot(playerId, newRoot);

        StringBuilder sb = new StringBuilder();
        sb.append("已修改 ").append(p.getName()).append(" (ID:").append(playerId).append(") 的灵根:\n");
        sb.append(oldRoot != null ? oldRoot.getDisplayName() : "无").append(" → ");
        sb.append(newRoot.getDisplayName()).append("（").append(newRoot.getTier().getDisplayName()).append("）\n");
        sb.append("基础属性已重新计算。");
        ctx.reply(sb.toString());
    }

    private String buildRootList() {
        StringBuilder sb = new StringBuilder();
        sb.append("===== 可用灵根列表 =====\n");

        SpiritualRoot.Tier currentTier = null;
        for (SpiritualRoot root : SpiritualRoot.values()) {
            if (currentTier != root.getTier()) {
                currentTier = root.getTier();
                sb.append("\n【").append(currentTier.getDisplayName()).append("】\n");
            }
            sb.append("  ").append(root.name())
                    .append(" - ").append(root.getDisplayName())
                    .append(" : ").append(root.getDescription()).append("\n");
        }
        sb.append("\n格式: /修改灵根 <玩家ID> <灵根名称>");
        return sb.toString();
    }
}
