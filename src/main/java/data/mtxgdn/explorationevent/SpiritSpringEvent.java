package data.mtxgdn.explorationevent;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;
import java.util.Random;

public class SpiritSpringEvent extends ExplorationEvent {
    public SpiritSpringEvent() {
        super("mtxgdn", "spirit_spring", 8);
    }

    @Override
    public void execute(Player player, PlayerService playerService,
                         ItemService itemService, Random random,
                         ExplorationResult result, List<String> log) {
        result.setEventType("spirit_spring");
        result.setEventDescription("灵泉沐浴");

        int healHp = player.getMaxHp() / 4 + random.nextInt(Math.max(1, player.getMaxHp() / 4));
        int healMp = player.getMaxMp() / 4 + random.nextInt(Math.max(1, player.getMaxMp() / 4));
        long exp = (player.getRealm() + 1) * 80L;
        int springCount = random.nextInt(1, 4);

        playerService.addExperience(player.getId(), exp);
        itemService.addItem(player.getId(), "mtxgdn:spirit_spring_water", springCount);
        result.setExpGained(exp);
        result.setItemGained("mtxgdn:spirit_spring_water");
        result.setItemQuantity(springCount);

        log.add("💧 你发现了一处隐秘的灵泉！泉水清澈见底，散发着浓郁的灵气。");
        log.add("你在灵泉中沐浴修炼，伤势恢复，灵力充盈...");
        log.add("HP 恢复 +" + healHp + "，MP 恢复 +" + healMp);
        log.add("获得了 " + exp + " 点经验和 灵泉水 x" + springCount + "。");

        // 走 PlayerService 而不是自己拼 SQL：
        // 1) 原来用的是 LEAST()，那是 MySQL 函数，SQLite 里不存在 —— 而默认数据库正是
        //    SQLite，于是每次触发灵泉都会抛「恢复生命法力失败」，整个探索事件直接失败；
        //    addHp/addMp 内部已经按方言分别用 MIN/LEAST，并且会夹到上限；
        // 2) 顺带补上 markDataDirty（在 addHp/addMp 里做），避免玩家快照与库里血量不一致；
        // 3) 内容层不该知道 players 表长什么样。
        playerService.addHp(player.getId(), healHp);
        playerService.addMp(player.getId(), healMp);

        result.setMessage("灵泉沐浴，恢复了生命和法力，获得 灵泉水 x" + springCount);
    }
}
