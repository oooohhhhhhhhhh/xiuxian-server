package com.mtxgdn.onebot.command.item;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.CommandUtils;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.PlayerActionLogger;
import java.util.Map;
import com.mtxgdn.i18n.I18n;

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
            ctx.reply(I18n.t("item_use.he8eb5bf0"));
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
