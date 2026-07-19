package com.mtxgdn.onebot.command.player;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Title;
import com.mtxgdn.game.title.TitleRegistry;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.List;
import java.util.Map;

public class TitleCommand extends Command {

    public TitleCommand() {
        super(new String[]{"称号", "title", "chenghao"},
                "查看/装备/卸下称号",
                "/称号 [装备|卸下|列表]",
                "我的角色",
                null);

        registerSub(new String[]{"列表", "list", "all"}, this::listAllTitles);
        registerSub(new String[]{"装备", "equip"}, this::equipTitle);
        registerSub(new String[]{"卸下", "unequip"}, this::unequipTitle);
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        showMyTitles(ctx, p);
    }

    private void showMyTitles(CommandContext ctx, PlayerInfo p) {
        var ts = ServiceRegistry.getTitleService();
        List<Map<String, Object>> titles = ts.getPlayerTitles(p.getId());
        Title equipped = ts.getEquippedTitle(p.getId());

        StringBuilder sb = new StringBuilder();
        sb.append("称号系统\n");
        sb.append("══════════\n");

        if (equipped != null) {
            sb.append("当前称号: ").append(equipped.getRarityColor()).append(equipped.getName());
            sb.append(" [").append(equipped.getRarityLabel()).append("]\n");
            sb.append(equipped.getDescription()).append("\n\n");
        }

        if (titles.isEmpty()) {
            sb.append("暂无任何称号。\n");
            sb.append("称号可通过活动、管理发放等方式获得。");
        } else {
            sb.append("已拥有称号 (").append(titles.size()).append("):\n");
            for (var t : titles) {
                String name = (String) t.get("name");
                String rarity = (String) t.get("rarityLabel");
                boolean isEquipped = (boolean) t.get("isEquipped");
                sb.append(isEquipped ? "  ▶ " : "    ");
                sb.append(name).append(" [").append(rarity).append("]");
                if (isEquipped) sb.append(" ← 当前");
                sb.append("\n");
            }
            sb.append("\n装备: /称号 装备 <名称> | 卸下: /称号 卸下");
        }

        ctx.reply(sb.toString());
    }

    private void listAllTitles(CommandContext ctx, PlayerInfo p, String[] parts) {
        TitleRegistry.init();
        var all = TitleRegistry.getAll();

        StringBuilder sb = new StringBuilder();
        sb.append("称号大全\n");
        sb.append("═══════\n");

        String currentRarity = "";
        for (Title t : all) {
            if (!t.getRarityLabel().equals(currentRarity)) {
                currentRarity = t.getRarityLabel();
                sb.append("\n【").append(currentRarity).append("】\n");
            }
            sb.append(t.getRarityColor()).append(t.getName())
              .append(" - ").append(t.getDescription()).append("\n");
            sb.append("  境界要求: Lv").append(t.getRequiredRealm());
            if (t.getAttackBonus() != 0) sb.append(" | 攻击+" + t.getAttackBonus());
            if (t.getHpBonus() != 0) sb.append(" | 生命+" + t.getHpBonus());
            if (t.getExpBonus() != 0) sb.append(" | 经验+" + (int)(t.getExpBonus() * 100) + "%");
            sb.append("\n");
        }

        ctx.reply(sb.toString());
    }

    private void equipTitle(CommandContext ctx, PlayerInfo p, String[] parts) {
        String arg = ctx.getArg();
        String name = "";
        if (arg != null) {
            name = arg.trim().replaceFirst("^装备\\s*", "").replaceFirst("^equip\\s*", "");
        }
        if (name.isEmpty()) {
            ctx.reply("用法: /称号 装备 <名称>\n先用 /称号 查看你拥有的称号");
            return;
        }

        var ts = ServiceRegistry.getTitleService();
        List<Map<String, Object>> titles = ts.getPlayerTitles(p.getId());

        String matchedKey = null;
        String matchedName = null;
        for (var t : titles) {
            String tName = (String) t.get("name");
            String tKey = (String) t.get("titleKey");
            if (tName.equals(name) || tName.contains(name) || tKey.equals(name)) {
                matchedKey = tKey;
                matchedName = tName;
                break;
            }
        }

        if (matchedKey == null) {
            ctx.reply("未找到称号「" + name + "」，请确认你是否拥有该称号。\n使用 /称号 查看已拥有的称号。");
            return;
        }

        var result = ts.equipTitle(p.getId(), matchedKey);
        ctx.reply((String) result.get("message"));
    }

    private void unequipTitle(CommandContext ctx, PlayerInfo p, String[] parts) {
        var ts = ServiceRegistry.getTitleService();
        var result = ts.unequipTitle(p.getId());
        ctx.reply((String) result.get("message"));
    }
}
