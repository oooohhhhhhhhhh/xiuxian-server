package com.mtxgdn.onebot.command.economy;

import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.EconomyService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.Map;
import com.mtxgdn.i18n.I18n;

/**
 * 金币 ↔ 下品灵石 双向兑换（汇率不对称，形成损耗）：
 * 10 金币 → 1 下品灵石（向下取整）；1 下品灵石 → 5 金币。
 * 此前 EconomyService 的两个兑换方法未暴露任何界面入口，此处补齐 QQ 指令。
 */
public class CurrencyExchangeCommand extends Command {

    private final EconomyService economyService = ServiceRegistry.getEconomyService();
    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();

    public CurrencyExchangeCommand() {
        super(new String[]{"货币兑换", "币兑", "currency_exchange"}, "金币与下品灵石双向兑换",
                "/货币兑换 金币 <数量> - 金币换灵石（10:1，向下取整）\n/货币兑换 灵石 <数量> - 灵石换金币（1:5）",
                "经济", "game.player.info");
        
        setI18nKeys("cmd.currency_exchange.desc", "cmd.currency_exchange.usage");
    setModule("economy");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) { ctx.reply(I18n.t("economy.hde5e9ffe")); return; }

        String args = ctx.getArg();
        if (args == null || args.trim().isEmpty()) {
            showHelp(ctx, p);
            return;
        }

        String[] parts = args.trim().split("\\s+");
        if (parts.length < 2) {
            ctx.reply(I18n.t("economy.hbe6f07f0"));
            return;
        }

        long amount;
        try {
            amount = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            ctx.reply(I18n.t("economy.h8c313f70"));
            return;
        }
        if (amount <= 0) {
            ctx.reply(I18n.t("economy.h893a9a8c"));
            return;
        }

        String direction = parts[0];
        Map<String, Object> result;
        if ("金币".equals(direction) || "gold".equalsIgnoreCase(direction)) {
            result = economyService.exchangeGoldToStones(p.getId(), amount);
        } else if ("灵石".equals(direction) || "stone".equalsIgnoreCase(direction) || "stones".equalsIgnoreCase(direction)) {
            result = economyService.exchangeStonesToGold(p.getId(), amount);
        } else {
            ctx.reply(I18n.t("economy.h610cfa8c"));
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
