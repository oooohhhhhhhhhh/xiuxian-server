package data.mtxgdn.explorationevent;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;
import java.util.Random;

public class RuinsEvent extends ExplorationEvent {
    public RuinsEvent() {
        super("mtxgdn", "ruins", 10);
    }

    @Override
    public void execute(Player player, PlayerService playerService,
                         ItemService itemService, Random random,
                         ExplorationResult result, List<String> log) {
        result.setEventType("ruins");
        result.setEventDescription("发现遗迹");

        double roll = random.nextDouble();
        if (roll < 0.4) {
            String[] rareItems = {"mtxgdn:cultivation_elixir", "mtxgdn:scripture_page", "mtxgdn:spirit_sword", "mtxgdn:guardian_jade",
                    "mtxgdn:basic_sword_manual", "mtxgdn:fire_dragon_art", "mtxgdn:jade_armor", "mtxgdn:power_buff_pill",
                    "mtxgdn:speed_talisman", "mtxgdn:thunder_bolt_talisman", "mtxgdn:dragon_blood_crystal", "mtxgdn:spirit_stone_pouch"};
            String rareItem = rareItems[random.nextInt(rareItems.length)];
            if (ItemRegistry.contains(rareItem)) {
                itemService.addItem(player.getId(), rareItem, 1);
                result.setItemGained(rareItem);
                result.setItemQuantity(1);
                log.add("🏛 你在遗迹深处发现了一件被封印的宝物！");
                result.setMessage("在遗迹中发现稀有宝物！");
            } else {
                long spiritStones = (player.getRealm() + 1) * 80L;
                itemService.addSpiritStones(player.getId(), spiritStones);
                result.setSpiritStonesGained(spiritStones);
                log.add("🏛 你发现了一处上古遗迹，但里面的宝物已被前人取走，只留下一些灵石。");
                log.add("获得了 " + spiritStones + " 灵石。");
                result.setMessage("遗迹寻宝，获得 " + spiritStones + " 灵石");
            }
        } else if (roll < 0.75) {
            long spiritStones = (player.getRealm() + 1) * 150L;
            itemService.addSpiritStones(player.getId(), spiritStones);
            result.setSpiritStonesGained(spiritStones);
            log.add("🏛 你发现了一处上古遗迹，在里面找到了大量灵石！");
            log.add("获得了 " + spiritStones + " 灵石。");
            result.setMessage("遗迹寻宝，获得 " + spiritStones + " 灵石");
        } else {
            long exp = (player.getRealm() + 1) * 200L;
            playerService.addExperience(player.getId(), exp);
            result.setExpGained(exp);
            log.add("🏛 你在遗迹的石壁上发现了上古功法残篇，潜心参悟...");
            log.add("获得了 " + exp + " 点经验。");
            result.setMessage("参悟功法残篇，获得 " + exp + " 点经验");
        }
    }
}
