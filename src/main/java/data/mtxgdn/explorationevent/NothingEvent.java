package data.mtxgdn.explorationevent;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;
import java.util.Random;

public class NothingEvent extends ExplorationEvent {
    public NothingEvent() {
        super("mtxgdn", "nothing", 5);
    }

    @Override
    public void execute(Player player, PlayerService playerService,
                         ItemService itemService, Random random,
                         ExplorationResult result, List<String> log) {
        result.setEventType("nothing");
        result.setEventDescription("一无所获");

        long exp = (player.getRealm() + 1) * 10L;
        playerService.addExperience(player.getId(), exp);
        result.setExpGained(exp);

        log.add("💨 你四处探索了一番，没有发现什么特别的东西...");
        log.add("不过漫步中也有感悟，获得了 " + exp + " 点经验。");
        result.setMessage("这次游历没什么收获，获得了 " + exp + " 点经验");
    }
}
