package data.mtxgdn.explorationevent;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.entity.Monster;
import com.mtxgdn.game.entity.PveCombatResult;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.service.CombatService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;
import java.util.Random;

public class MonsterEvent extends ExplorationEvent {
    public MonsterEvent() {
        super("mtxgdn", "monster", 25);
    }

    @Override
    public void execute(Player player, PlayerService playerService,
                         ItemService itemService, Random random,
                         ExplorationResult result, List<String> log) {
        result.setEventType("monster");
        result.setEventDescription("遭遇妖兽");

        Monster monster = Monster.random(player.getRealm(), random);
        CombatService combatService = new CombatService();
        PveCombatResult pveResult = combatService.pveFight(player.getId(), monster);

        result.setMonsterDefeated(pveResult.isPlayerWon());
        result.setMonsterName(monster.getName());
        result.setExpGained(pveResult.getExpGained());
        result.setGoldGained(pveResult.getGoldGained());
        result.setSpiritStonesGained(pveResult.getSpiritStonesGained());
        result.setItemGained(pveResult.getItemGained());
        result.setItemQuantity(pveResult.getItemQuantity());
        result.setMessage(pveResult.getMessage());

        if (pveResult.getBattleLog() != null) {
            log.addAll(pveResult.getBattleLog());
        }
    }
}
