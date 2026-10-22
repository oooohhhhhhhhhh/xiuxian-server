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
        int hpLoss = maxHp / 10 + random.nextInt(Math.max(1, maxHp / 8));
        result.setHpLost(hpLoss);

        // 走 PlayerService 而不是直接 UPDATE players：
        // 1) 原来的写法写入的是"读内存快照算出来的绝对值"，并发下会覆盖掉别的通道
        //    刚做的治疗/伤害（lost update）；
        // 2) 绕过了 markDataDirty，玩家快照可能与库里的血量不一致；
        // 3) 内容层不该知道 players 表长什么样。
        // floor=1：陷阱不该直接打死玩家。
        playerService.damageHp(player.getId(), hpLoss, 1);

        log.add("⚠ 你不慎触发了古老的机关陷阱！损失了 " + hpLoss + " 点生命值。");
        result.setMessage("触发陷阱，损失了 " + hpLoss + " 点生命值");
    }
}
