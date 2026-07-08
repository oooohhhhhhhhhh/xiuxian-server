package com.mtxgdn.onebot.command.item;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.util.LangManager;
import java.util.*;

public class ItemMapCommand extends Command {
    public ItemMapCommand() { super(new String[]{"物品列表", "itemmap"}, "查看物品中文名与key映射表", "/物品列表", "背包", "game.player.info"); }
    @Override
    public void execute(CommandContext ctx) {
        Collection<Item> allItems = ItemRegistry.getAll();
        if (allItems.isEmpty()) { ctx.reply("物品列表为空。"); return; }
        Map<String, List<Item>> byType = new LinkedHashMap<>();
        for (Item item : allItems) {
            byType.computeIfAbsent(item.getType().getDisplayName(), k -> new ArrayList<>()).add(item);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("===== 物品映射表 =====\n");
        sb.append("支持中文名和key，例如: /使用 回血丹\n");
        sb.append("================================\n");
        for (Map.Entry<String, List<Item>> entry : byType.entrySet()) {
            sb.append("\n【").append(entry.getKey()).append("】\n");
            for (Item item : entry.getValue()) {
                String translatedName = LangManager.get(item.getNameKey());
                String displayName = (translatedName != null && !translatedName.isEmpty())
                        ? translatedName : item.getFullKey();
                sb.append("  ").append(displayName).append("\n");
                sb.append("    key: ").append(item.getFullKey());
                sb.append(" | ").append(item.getRarity().getDisplayName());
                sb.append(" | ").append(item.getDescription()).append("\n");
            }
        }
        sb.append("\n================================\n");
        sb.append("共 ").append(allItems.size()).append(" 个物品\n");
        sb.append("所有涉及物品的指令均支持中文名和key混用");
        ctx.reply(sb.toString());
    }
}
