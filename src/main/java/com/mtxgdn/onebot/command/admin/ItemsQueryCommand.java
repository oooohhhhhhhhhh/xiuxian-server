package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ItemsQueryCommand extends Command {
    public ItemsQueryCommand() {
        super(new String[]{"物品列表", "items", "allitems"},
                "查看所有物品列表（仅私聊，支持分页）",
                "/物品列表 [页数=1]",
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

        int page = 1;
        int pageSize = 20;
        String arg = ctx.getArg();
        if (arg != null && !arg.isBlank()) {
            try {
                page = Integer.parseInt(arg.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        List<Item> allItems = new ArrayList<>(ItemRegistry.getAll());
        int total = allItems.size();
        int totalPages = (total + pageSize - 1) / pageSize;
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, total);

        StringBuilder sb = new StringBuilder();
        sb.append("===== 物品列表 (第").append(page).append("/").append(totalPages).append("页) =====\n");
        sb.append("共 ").append(total).append(" 种物品\n\n");

        for (int i = start; i < end; i++) {
            Item item = allItems.get(i);
            sb.append("[").append(item.getType().name()).append("] ")
                    .append(item.getFullKey())
                    .append(" - ").append(item.getName())
                    .append(" (").append(item.getRarity().name()).append(")\n");
        }

        if (totalPages > 1) {
            sb.append("\n输入 /物品列表 ").append(page + 1 > totalPages ? 1 : page + 1).append(" 查看下一页");
        }
        ctx.reply(sb.toString());
    }
}
