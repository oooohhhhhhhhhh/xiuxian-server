package com.mtxgdn.onebot.command.item;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.Map;

public class EnhanceCommand extends Command {
    public EnhanceCommand() {
        super(new String[]{"强化", "enhance"},
                "强化已装备的装备",
                "/强化 <装备栏位>",
                "装备",
                "game.equipment.enhance");
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
            ctx.reply("用法: /强化 <装备栏位>\n栏位: weapon(武器) | armor(防具) | accessory(饰品)");
            return;
        }

        String slot = arg.trim().toLowerCase();
        String[] validSlots = {"weapon", "armor", "accessory"};
        boolean valid = false;
        for (String s : validSlots) {
            if (s.equals(slot)) { valid = true; break; }
        }
        if (!valid) {
            ctx.reply("无效的装备栏位: " + slot + "\n栏位: weapon(武器) | armor(防具) | accessory(饰品)");
            return;
        }

        var enhanceService = ServiceRegistry.getEnhanceService();
        Map<String, Object> result = enhanceService.enhanceItem((long) p.getId(), slot);

        StringBuilder sb = new StringBuilder();
        sb.append("===== 装备强化 =====\n");
        sb.append("装备: ").append(result.getOrDefault("itemName", "?")).append("\n");
        sb.append("栏位: ").append(slot).append("\n");
        sb.append("当前等级: +").append(result.getOrDefault("currentLevel", 0)).append("\n");
        sb.append("成功率: ").append(result.getOrDefault("successRate", 0)).append("%\n");
        sb.append("消耗: ").append(result.getOrDefault("costGold", 0)).append("金币 + ")
          .append(result.getOrDefault("costStones", 0)).append("强化石\n\n");

        if (Boolean.TRUE.equals(result.get("success"))) {
            Boolean enhanceSuccess = (Boolean) result.get("enhanceSuccess");
            if (Boolean.TRUE.equals(enhanceSuccess)) {
                sb.append("强化成功！装备强化等级提升至 +").append(result.get("newLevel"));
            } else {
                sb.append("强化失败！");
                String failReason = (String) result.get("failReason");
                if (failReason != null) sb.append(failReason);
            }
        } else {
            sb.append((String) result.get("message"));
        }

        ctx.reply(sb.toString());
    }
}
