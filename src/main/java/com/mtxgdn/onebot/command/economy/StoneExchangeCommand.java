package com.mtxgdn.onebot.command.economy;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.CurrencyEffect;
import com.mtxgdn.game.service.ItemService;

import java.util.Map;

public class StoneExchangeCommand extends Command {

    private final ItemService itemService = new ItemService();

    private static final String[] STONE_NAMES = CurrencyEffect.GRADE_NAMES;

    public StoneExchangeCommand() {
        super(new String[]{"灵石兑换", "石兑"}, "兑换不同品质的灵石",
                "/灵石兑换 [数量] <从> <到>\n示例: /灵石兑换 1 极品 下品",
                "经济", "game.player.info");
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
        if (parts.length < 3) {
            ctx.reply("用法: /灵石兑换 [数量] <从> <到>\n示例: /灵石兑换 1 极品 下品");
            return;
        }

        long amount;
        try {
            amount = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            ctx.reply("数量必须是数字");
            return;
        }

        int fromGrade = parseGrade(parts[1]);
        int toGrade = parseGrade(parts[2]);

        if (fromGrade < 0) {
            ctx.reply("无效的来源灵石等级: " + parts[1] + "（可选: 下品/中品/上品/极品）");
            return;
        }
        if (toGrade < 0) {
            ctx.reply("无效的目标灵石等级: " + parts[2] + "（可选: 下品/中品/上品/极品）");
            return;
        }

        Map<String, Object> result = itemService.exchangeSpiritStones(p.getId(), fromGrade, toGrade, amount);
        ctx.reply((String) result.get("message"));
    }

    private int parseGrade(String name) {
        return switch (name) {
            case "下品", "low", "0" -> 0;
            case "中品", "mid", "1" -> 1;
            case "上品", "high", "2" -> 2;
            case "极品", "supreme", "3" -> 3;
            default -> -1;
        };
    }

    private void showHelp(CommandContext ctx, PlayerInfo p) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== 灵石兑换 =====\n");
        sb.append("兑换规则: 只能从高等级兑换为低等级\n");
        sb.append("兑换比例: 1:1000（如1中品=1000下品）\n\n");
        sb.append("用法: /灵石兑换 [数量] <从> <到>\n");
        sb.append("示例:\n");
        sb.append("  /灵石兑换 1 中品 下品\n");
        sb.append("  /灵石兑换 5 上品 中品\n");
        sb.append("  /灵石兑换 1 极品 下品\n\n");
        sb.append("当前灵石余额:\n");
        for (int i = 0; i < 4; i++) {
            long count = itemService.getSpiritStoneCount(p.getId(), i);
            sb.append("  ").append(STONE_NAMES[i]).append(": ").append(count).append("\n");
        }
        sb.append("\n总计: ").append(itemService.getSpiritStoneCount(p.getId())).append(" 下品灵石");
        ctx.reply(sb.toString());
    }
}