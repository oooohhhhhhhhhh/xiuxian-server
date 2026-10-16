package com.mtxgdn.onebot.command.economy;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.EconomyService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.Map;

/**
 * 金币 ↔ 下品灵石 双向兑换（汇率不对称，形成损耗）：
 * 10 金币 → 1 下品灵石（向下取整）；1 下品灵石 → 5 金币。
 * 此前 EconomyService 的两个兑换方法未暴露任何界面入口，此处补齐 QQ 指令。
 */
public class CurrencyExchangeCommand extends Command {

    private final EconomyService economyService = new EconomyService();
    private final PlayerService playerService = new PlayerService();
    private final ItemService itemService = new ItemService();

    public CurrencyExchangeCommand() {
        super(new String[]{"货币兑换", "币兑", "currency_exchange"}, "金币与下品灵石双向兑换",
                "/货币兑换 金币 <数量> - 金币换灵石（10:1，向下取整）\n/货币兑换 灵石 <数量> - 灵石换金币（1:5）",
                "经济", "game.player.info");
        setModule("economy");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) { ctx.reply("请先创建角色"); return; }

        String args = ctx.getArg();
        if (args == null || args.trim().isEmpty()) {
            showHelp(ctx, p);
            return;
        }

        String[] parts = args.trim().split("\\s+");
        if (parts.length < 2) {
            ctx.reply("用法: /货币兑换 金币 <数量> 或 /货币兑换 灵石 <数量>");
            return;
        }

        long amount;
        try {
            amount = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            ctx.reply("数量必须是正整数");
            return;
        }
        if (amount <= 0) {
            ctx.reply("数量必须大于 0");
            return;
        }

        String direction = parts[0];
        Map<String, Object> result;
        if ("金币".equals(direction) || "gold".equalsIgnoreCase(direction)) {
            result = economyService.exchangeGoldToStones(p.getId(), amount);
        } else if ("灵石".equals(direction) || "stone".equalsIgnoreCase(direction) || "stones".equalsIgnoreCase(direction)) {
            result = economyService.exchangeStonesToGold(p.getId(), amount);
        } else {
            ctx.reply("方向只能是: 金币（换灵石）或 灵石（换金币）");
            return;
        }

        ctx.reply((String) result.get("message"));
    }

    private void showHelp(CommandContext ctx, PlayerInfo p) {
        Player player = playerService.getPlayerById(p.getId());
        long gold = player != null ? player.getGold() : 0;
        long lowStones = itemService.getSpiritStoneCount(p.getId(), 0);

        StringBuilder sb = new StringBuilder();
        sb.append("===== 货币兑换 =====\n");
        sb.append("兑换规则（汇率不对称，双向均有损耗）:\n");
        sb.append("  10 金币 → 1 下品灵石（向下取整）\n");
        sb.append("  1 下品灵石 → 5 金币\n\n");
        sb.append("用法:\n");
        sb.append("  /货币兑换 金币 <数量>  金币换灵石\n");
        sb.append("  /货币兑换 灵石 <数量>  灵石换金币\n\n");
        sb.append("当前余额: 金币 ").append(gold).append(" | 下品灵石 ").append(lowStones);
        ctx.reply(sb.toString());
    }
}
