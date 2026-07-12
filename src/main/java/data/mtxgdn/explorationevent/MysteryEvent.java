package data.mtxgdn.explorationevent;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;
import java.util.Random;

public class MysteryEvent extends ExplorationEvent {
    public MysteryEvent() {
        super("mtxgdn", "mystery", 8);
    }

    @Override
    public void execute(Player player, PlayerService playerService,
                         ItemService itemService, Random random,
                         ExplorationResult result, List<String> log) {
        result.setEventType("mystery");
        result.setEventDescription("神秘奇遇");

        double roll = random.nextDouble();

        if (roll < 0.2) {
            String[] rareItems = {"mtxgdn:five_colored_divine_stone", "mtxgdn:phoenix_gallbladder", "mtxgdn:qilin_horn", "mtxgdn:dragon_scale", "mtxgdn:phoenix_feather", "mtxgdn:dragon_bone"};
            String itemKey = rareItems[random.nextInt(rareItems.length)];
            if (ItemRegistry.contains(itemKey)) {
                itemService.addItem(player.getId(), itemKey, 1);
                result.setItemGained(itemKey);
                result.setItemQuantity(1);
                log.add("🌟 你在一处古老的遗迹中发现了一件传说中的物品！");
                log.add("获得了珍稀材料！");
                result.setMessage("神秘奇遇！获得一件传说材料");
            } else {
                long stones = (player.getRealm() + 1) * 100L;
                itemService.addSpiritStones(player.getId(), stones);
                result.setSpiritStonesGained(stones);
                log.add("🌟 你发现了一处古老的祭坛，上面放着一些灵石。");
                log.add("获得了 " + stones + " 灵石！");
                result.setMessage("神秘奇遇！获得 " + stones + " 灵石");
            }
        } else if (roll < 0.4) {
            long gold = random.nextLong(500L, 2000L);
            playerService.addGold(player.getId(), gold);
            result.setGoldGained(gold);
            log.add("💰 你在路边捡到一个钱袋，里面装满了金币！");
            log.add("获得了 " + gold + " 金币！");
            result.setMessage("神秘奇遇！获得 " + gold + " 金币");
        } else if (roll < 0.6) {
            long exp = (player.getRealm() + 1) * 200L;
            playerService.addExperience(player.getId(), exp);
            result.setExpGained(exp);
            log.add("📖 你发现了一本古老的修炼秘籍残页，研读后受益匪浅。");
            log.add("获得了 " + exp + " 点经验！");
            result.setMessage("神秘奇遇！研读秘籍获得 " + exp + " 经验");
        } else if (roll < 0.75) {
            String[] seeds = {"mtxgdn:spirit_grass_seed", "mtxgdn:dark_ice_grass_seed", "mtxgdn:fire_vine_seed", "mtxgdn:thousand_year_ginseng_seed", "mtxgdn:nether_flower_seed"};
            String seedKey = seeds[random.nextInt(seeds.length)];
            int qty = random.nextInt(2, 4);
            if (ItemRegistry.contains(seedKey)) {
                itemService.addItem(player.getId(), seedKey, qty);
                result.setItemGained(seedKey);
                result.setItemQuantity(qty);
                log.add("🌱 你在一处神秘的花园中发现了许多珍稀种子！");
                log.add("获得了 " + qty + " 颗珍贵种子！");
                result.setMessage("神秘奇遇！获得 " + qty + " 颗珍贵种子");
            }
        } else if (roll < 0.9) {
            long stones = random.nextLong(50L, 200L);
            itemService.addSpiritStones(player.getId(), stones);
            result.setSpiritStonesGained(stones);
            log.add("💎 你在山间小溪边发现了几块闪闪发光的灵石！");
            log.add("获得了 " + stones + " 灵石！");
            result.setMessage("神秘奇遇！获得 " + stones + " 灵石");
        } else {
            long exp = (player.getRealm() + 1) * 500L;
            playerService.addExperience(player.getId(), exp);
            result.setExpGained(exp);
            log.add("✨ 你无意中踏入了一处灵气充沛的秘境，修炼效果倍增！");
            log.add("获得了 " + exp + " 点经验！");
            result.setMessage("神秘奇遇！误入秘境获得 " + exp + " 经验");
        }
    }
}
