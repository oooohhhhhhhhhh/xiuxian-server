package com.mtxgdn.onebot.command.item;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.CommandUtils;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.PlayerActionLogger;
import java.util.Map;

public class ItemUseCommand extends Command {
    public ItemUseCommand() {
        super(new String[]{"使用", "itemuse"}, "使用背包中的物品", "/使用 <物品名称或key>", "背包", "game.item.use");
        setModule("inventory");
    }
    @Override
    public void execute(CommandContext ctx) {
        PlayerInfo p = requirePlayerInfo(ctx);
        if (p == null) return;

        Long userId = p.getUserId();
        String itemKey = ctx.getArg();
        if (CommandUtils.isBlank(itemKey)) {
            ctx.reply("用法: /使用 <物品名称或key>\n可在 /背包 或 /物品列表 中查看你的物品\n例如: /使用 回血丹");
            return;
        }
        var itemService = ServiceRegistry.getItemService();
        var guideService = ServiceRegistry.getGuideService();
        var actionLog = PlayerActionLogger.getInstance();
        Map<String, Object> useResult = itemService.useItem((int) p.getId(), itemKey);
        boolean success = (boolean) useResult.getOrDefault("success", false);
        String msg = (String) useResult.getOrDefault("message", "");
        actionLog.logItemUse(userId, p.getName(), itemKey, success, msg);
        String discTip = guideService.checkDiscovery((int) p.getId(), p, "item_use");
        if (discTip != null) msg += "\n\n" + discTip;
        ctx.reply(msg);
    }
}
