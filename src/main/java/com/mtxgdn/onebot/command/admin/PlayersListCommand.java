package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;

import java.util.List;

public class PlayersListCommand extends Command {
    public PlayersListCommand() {
        super(new String[]{"玩家列表", "players", "playerslist"},
                "查看玩家列表（仅私聊，支持分页）",
                "/玩家列表 [页数=1] [每页=10] 或 /玩家列表 <搜索名>",
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

        PlayerService playerService = ServiceRegistry.getPlayerService();
        ItemService itemService = ServiceRegistry.getItemService();

        String arg = ctx.getArg();
        int page = 1;
        int pageSize = 10;

        if (arg != null && !arg.isBlank()) {
            String[] parts = arg.trim().split("\\s+");
            if (parts.length == 1) {
                try {
                    page = Integer.parseInt(parts[0]);
                } catch (NumberFormatException e) {
                    // search by name
                    List<PlayerInfo> players = playerService.searchPlayersByName(parts[0], 20, 0);
                    ctx.reply(formatPlayersList(players, itemService, "搜索 \"" + parts[0] + "\" 结果"));
                    return;
                }
            } else if (parts.length >= 2) {
                try {
                    page = Integer.parseInt(parts[0]);
                    pageSize = Math.min(Integer.parseInt(parts[1]), 50);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        int offset = (page - 1) * pageSize;
        int total = playerService.getPlayerCount();
        List<PlayerInfo> players = playerService.getAllPlayers(pageSize, offset);
        int totalPages = (total + pageSize - 1) / pageSize;

        StringBuilder sb = new StringBuilder();
        sb.append("===== 玩家列表 (第").append(page).append("/").append(totalPages).append("页) =====\n");
        sb.append("共 ").append(total).append(" 名玩家\n\n");
        sb.append(formatPlayersList(players, itemService, null));

        if (totalPages > 1) {
            sb.append("\n输入 /玩家列表 ").append(page + 1 > totalPages ? 1 : page + 1)
                    .append(" ").append(pageSize).append(" 查看下一页");
        }
        ctx.reply(sb.toString());
    }

    private String formatPlayersList(List<PlayerInfo> players, ItemService itemService, String title) {
        StringBuilder sb = new StringBuilder();
        if (title != null) sb.append("【").append(title).append("】\n");
        for (PlayerInfo p : players) {
            sb.append("ID:").append(p.getId())
                    .append(" ").append(p.getName())
                    .append(" | ").append(p.getRealmName() != null ? p.getRealmName() : "Lv." + p.getRealm())
                    .append(" Lv.").append(p.getLevel());
            long ss = itemService.getSpiritStoneCount(p.getId());
            sb.append(" | 灵石:").append(ss);
            if (p.isCultivating()) sb.append(" [修炼中]");
            sb.append("\n");
        }
        if (players.isEmpty()) sb.append("无。\n");
        return sb.toString();
    }
}
