package data.mtxgdn.explorationevent;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;
import java.util.Random;

public class TreasureEvent extends ExplorationEvent {
    public TreasureEvent() {
        super("mtxgdn", "treasure", 25);
    }

    @Override
    public void execute(Player player, PlayerService playerService,
                         ItemService itemService, Random random,
                         ExplorationResult result, List<String> log) {
        result.setEventType("treasure");
        result.setEventDescription("发现宝藏");

        int realm = player.getRealm() + 1;
        long gold = random.nextLong(realm * 50L, realm * 300L + 100);
        long spiritStones = random.nextLong(realm * 10L, realm * 80L + 20);

        playerService.addGold(player.getId(), gold);
        itemService.addSpiritStones(player.getId(), spiritStones);

        result.setGoldGained(gold);
        result.setSpiritStonesGained(spiritStones);

        log.add("✨ 你发现了一个隐藏的宝箱！");
        log.add("获得了 " + gold + " 金币和 " + spiritStones + " 灵石！");
        result.setMessage("发现宝藏！获得了 " + gold + " 金币和 " + spiritStones + " 灵石");
    }
}
