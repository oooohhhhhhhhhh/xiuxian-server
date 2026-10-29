package com.mtxgdn.onebot.command.item;

import com.mtxgdn.game.item.EnhanceResult;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.Map;
import com.mtxgdn.i18n.I18n;

public class EnhanceCommand extends Command {
    public EnhanceCommand() {
        super(new String[]{"强化", "enhance"},
                "强化已装备的装备",
                "/强化 <装备栏位>",
                "装备",
                "game.equipment.enhance");
        
        setI18nKeys("cmd.enhance.desc", "cmd.enhance.usage");
    setModule("inventory");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        if (!ctx.checkPermission("game.equipment.enhance")) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        String arg = ctx.getArg();
        if (arg == null || arg.trim().isEmpty()) {
            ctx.reply(I18n.t("enhance.usage"));
            return;
        }

        String slot = arg.trim().toLowerCase();
        String[] validSlots = {"weapon", "armor", "accessory"};
        boolean valid = false;
        for (String s : validSlots) {
            if (s.equals(slot)) { valid = true; break; }
        }
        if (!valid) {
            ctx.reply(I18n.t("enhance.invalid_equipment_slot", slot));
            return;
        }

        var enhanceService = ServiceRegistry.getEnhanceService();
        EnhanceResult result = enhanceService.enhanceItem((long) p.getId(), slot);

        StringBuilder sb = new StringBuilder();
        sb.append("===== 装备强化 =====\n");
        sb.append("装备: ").append(result.itemName() != null ? result.itemName() : "?").append("\n");
        sb.append("栏位: ").append(slot).append("\n");
        sb.append("当前等级: +").append(result.currentLevel() != null ? result.currentLevel() : 0).append("\n");
        sb.append("成功率: ").append(result.successRate() != null ? result.successRate() : 0).append("%\n");
        sb.append("消耗: ").append(result.costGold() != null ? result.costGold() : 0).append("金币 + ")
          .append(result.costStones() != null ? result.costStones() : 0).append("强化石\n\n");

        if (result.success()) {
            if (Boolean.TRUE.equals(result.enhanceSuccess())) {
                sb.append("强化成功！装备强化等级提升至 +").append(result.newLevel());
            } else {
                sb.append("强化失败！");
            }
        } else {
            sb.append(result.message());
        }

        ctx.reply(sb.toString());
    }
}
