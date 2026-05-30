package data.mtxgdn.explorationevent;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;
import java.util.Random;

public class CultivatorEvent extends ExplorationEvent {
    public CultivatorEvent() {
        super("mtxgdn", "cultivator", 13);
    }

    @Override
    public void execute(Player player, PlayerService playerService,
                         ItemService itemService, Random random,
                         ExplorationResult result, List<String> log) {
        result.setEventType("cultivator");
        result.setEventDescription("遇到散修");

        double roll = random.nextDouble();
        if (roll < 0.5) {
            long exp = (player.getRealm() + 1) * 120L;
            playerService.addExperience(player.getId(), exp);
            result.setExpGained(exp);
            log.add("👤 你遇到了一位路过的散修，他与你交流修炼心得，你获益匪浅。");
            log.add("获得了 " + exp + " 点经验。");
            result.setMessage("与散修交流，获得 " + exp + " 点经验");
        } else if (roll < 0.8) {
            long spiritStones = (player.getRealm() + 1) * 30L;
            itemService.addSpiritStones(player.getId(), spiritStones);
            result.setSpiritStonesGained(spiritStones);
            log.add("👤 一位好心的散修送了你一些灵石作为见面礼。");
            log.add("获得了 " + spiritStones + " 灵石。");
            result.setMessage("散修赠礼，获得 " + spiritStones + " 灵石");
        } else {
            String[] items = {"spirit_grass", "iron_ore", "healing_pill", "mana_pill", "spirit_recovery_pill"};
            String giftItem = items[random.nextInt(items.length)];
            if (ItemRegistry.contains(giftItem) && random.nextDouble() < 0.6) {
                itemService.addItem(player.getId(), giftItem, 1);
                result.setItemGained(giftItem);
                result.setItemQuantity(1);
                log.add("👤 你遇到了一位云游四方的散修，临别时他赠予你一件物品。");
                result.setMessage("散修赠予了一件物品");
            } else {
                long gold = (player.getRealm() + 1) * 100L;
                playerService.addGold(player.getId(), gold);
                result.setGoldGained(gold);
                log.add("👤 你在路上遇到一位散修，他慷慨地与你分享了盘缠。");
                log.add("获得了 " + gold + " 金币。");
                result.setMessage("散修赠金，获得 " + gold + " 金币");
            }
        }
    }
}
