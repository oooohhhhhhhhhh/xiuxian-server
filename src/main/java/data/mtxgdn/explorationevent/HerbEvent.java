package data.mtxgdn.explorationevent;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;
import java.util.Random;

public class HerbEvent extends ExplorationEvent {
    public HerbEvent() {
        super("mtxgdn", "herb", 15);
    }

    @Override
    public void execute(Player player, PlayerService playerService,
                         ItemService itemService, Random random,
                         ExplorationResult result, List<String> log) {
        result.setEventType("herb");
        result.setEventDescription("采集灵草");

        String[] herbs = {"mtxgdn:spirit_grass", "mtxgdn:healing_pill", "mtxgdn:mana_pill", "mtxgdn:spirit_spring_water"};
        String herbItem = herbs[random.nextInt(herbs.length)];
        int quantity = random.nextInt(1, 3);

        if (herbItem != null && ItemRegistry.contains(herbItem)) {
            itemService.addItem(player.getId(), herbItem, quantity);
            result.setItemGained(herbItem);
            result.setItemQuantity(quantity);
            log.add("🌿 你发现了一株珍贵的灵草！");
            result.setMessage("采集到了灵草！");
        } else {
            long exp = (player.getRealm() + 1) * 30L;
            playerService.addExperience(player.getId(), exp);
            result.setExpGained(exp);
            log.add("🌿 你发现了一些普通的草药，虽然不算珍贵，但也有些收获。");
            log.add("获得了 " + exp + " 点经验。");
            result.setMessage("采集草药，获得 " + exp + " 点经验");
        }
    }
}
