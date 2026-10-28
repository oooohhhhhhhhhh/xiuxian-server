package com.mtxgdn.onebot.command.item;

import com.mtxgdn.common.result.OperationResult;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.DurabilityService;
import com.mtxgdn.i18n.I18n;

import java.util.Map;

/**
 * 装备修理。
 *
 * <p>耐久在战斗（PVE/PVP/团队副本）中消耗，归零后该装备的属性与强化加成全部失效，
 * 且<b>不允许卸下</b>（否则"坏了就脱下来再装上"等于免费修理）。修理按缺失的耐久点数计价，
 * 单价取物品价格的固定分之一，因此越贵的装备修起来越贵。</p>
 *
 * <p>刻意<b>不覆写 {@link Command#execute}</b>：默认实现负责绑定解析、权限校验与玩家查询，
 * 覆写它就得把这些逻辑再抄一遍（现有若干指令正是这样漏掉了权限检查）。这里只用
 * {@code onDefault}/{@code registerSub} 挂子命令。</p>
 */
public class RepairCommand extends Command {

    public RepairCommand() {
        super(new String[]{"修理", "repair"},
                "修理装备耐久",
                "/修理 - 查看装备耐久与修理费用\n"
                        + "/修理 武器|护甲|饰品|全部 - 执行修理",
                "背包",
                "game.equipment.repair");

        setI18nKeys("cmd.repair.desc", "cmd.repair.usage");
        setModule("inventory");

        registerSub(new String[]{"武器", "weapon"}, (ctx, p, parts) -> doRepair(ctx, p, "weapon"));
        registerSub(new String[]{"护甲", "防具", "armor"}, (ctx, p, parts) -> doRepair(ctx, p, "armor"));
        registerSub(new String[]{"饰品", "accessory"}, (ctx, p, parts) -> doRepair(ctx, p, "accessory"));
        registerSub(new String[]{"全部", "所有", "all"}, (ctx, p, parts) -> doRepair(ctx, p, "all"));
    }

    /** 无参数：展示耐久面板而不是直接扣钱，让玩家先看清修一次要花多少。 */
    @Override
    protected void onDefault(CommandContext ctx, PlayerInfo p) {
        ctx.reply(buildPanel(p));
    }

    @Override
    protected void onUnknown(CommandContext ctx, PlayerInfo p, String sub, String[] parts) {
        ctx.reply(I18n.t("equip.repair_bad_slot"));
    }

    private void doRepair(CommandContext ctx, PlayerInfo p, String slot) {
        OperationResult result = ServiceRegistry.getDurabilityService().repair(p.getId(), slot);
        ctx.reply(result.message());
    }

    private String buildPanel(PlayerInfo p) {
        DurabilityService durability = ServiceRegistry.getDurabilityService();
        Map<String, String> equipment = ServiceRegistry.getItemService().getEquipment(p.getId());
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.t("equip.repair_panel_title")).append("\n");

        int max = DurabilityService.getMaxDurability();
        int divisor = Math.max(1, com.mtxgdn.util.AppConfig.getInt("equipment.repair_price_divisor", 50));
        long totalCost = 0;
        boolean hasAny = false;
        boolean hasBroken = false;

        for (String slot : DurabilityService.SLOTS) {
            String itemKey = equipment.get(slot);
            String label = DurabilityService.getSlotDisplayName(slot);
            if (itemKey == null) {
                sb.append(label).append(": ").append(I18n.t("equip.slot_empty")).append("\n");
                continue;
            }
            hasAny = true;
            Item item = ItemRegistry.get(itemKey);
            int current = durability.getDurability(p.getId(), itemKey);
            long cost = 0;
            if (current < max && item != null) {
                int unit = Math.max(1, item.getPrice() / divisor);
                cost = (long) (max - current) * unit;
                totalCost += cost;
            }
            if (current <= 0) hasBroken = true;

            sb.append(label).append(": ")
                    .append(item != null ? item.getName() : itemKey)
                    .append(" ").append(current).append("/").append(max);
            if (current <= 0) {
                sb.append(" ").append(I18n.t("equip.broken_tag"));
            } else if (current < max) {
                sb.append(" ").append(I18n.t("equip.repair_cost_tag", cost));
            }
            sb.append("\n");
        }

        if (!hasAny) {
            sb.append(I18n.t("equip.repair_no_equipment"));
            return sb.toString();
        }
        if (totalCost > 0) {
            sb.append(I18n.t("equip.repair_total_cost", totalCost));
        } else {
            sb.append(I18n.t("equip.repair_full"));
        }
        if (hasBroken) {
            sb.append("\n").append(I18n.t("equip.repair_broken_hint"));
        }
        return sb.toString();
    }
}
