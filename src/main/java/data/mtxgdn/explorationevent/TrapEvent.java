package data.mtxgdn.explorationevent;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;
import java.util.Random;

public class TrapEvent extends ExplorationEvent {
    public TrapEvent() {
        super("mtxgdn", "trap", 7);
    }

    @Override
    public void execute(Player player, PlayerService playerService,
                         ItemService itemService, Random random,
                         ExplorationResult result, List<String> log) {
        result.setEventType("trap");
        result.setEventDescription("遭遇陷阱");

        int maxHp = player.getMaxHp();
        int hpLoss = maxHp / 10 + random.nextInt(maxHp / 8);
        int newHp = Math.max(1, player.getHp() - hpLoss);
        result.setHpLost(hpLoss);

        try (var conn = com.mtxgdn.db.DatabaseManager.getConnection();
             var ps = conn.prepareStatement("UPDATE players SET hp = ? WHERE id = ?")) {
            ps.setInt(1, newHp);
            ps.setLong(2, player.getId());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("更新生命值失败", e);
        }

        log.add("⚠ 你不慎触发了古老的机关陷阱！损失了 " + hpLoss + " 点生命值。");
        result.setMessage("触发陷阱，损失了 " + hpLoss + " 点生命值");
    }
}
