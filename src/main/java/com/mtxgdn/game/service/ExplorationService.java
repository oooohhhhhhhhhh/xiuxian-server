package com.mtxgdn.game.service;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.explorationevent.ExplorationEventRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ExplorationService {

    private static final long COOLDOWN_MS = 60_000;

    private final PlayerService playerService;
    private final ItemService itemService;
    private final Random random = new Random();

    public ExplorationService() {
        this.playerService = new PlayerService();
        this.itemService = new ItemService();
    }

    public ExplorationService(PlayerService playerService) {
        this.playerService = playerService;
        this.itemService = new ItemService();
    }

    public ExplorationResult explore(long userId) {
        Player player = playerService.getPlayerRaw(userId);
        if (player == null) {
            return ExplorationResult.failure("角色不存在，请先创建角色");
        }

        long now = System.currentTimeMillis();
        long effectiveCd = COOLDOWN_MS;
        if (player.getSpiritualRoot() != null && player.getSpiritualRoot().hasEffect(SpiritualRoot.SpecialEffect.EXPLORATION_CD)) {
            effectiveCd = (long)(COOLDOWN_MS * (1 - player.getSpiritualRoot().getEffectValue()));
        }
        long lastTime = player.getLastExplorationTime();
        if (lastTime > 0 && (now - lastTime) < effectiveCd) {
            long remaining = (effectiveCd - (now - lastTime)) / 1000;
            return ExplorationResult.failure("你刚游历过，还需要等待 " + remaining + " 秒");
        }

        if (player.getHp() <= 0) {
            return ExplorationResult.failure("你已重伤，无法游历，请先恢复生命值");
        }

        playerService.updateLastExplorationTime(player.getId(), now);

        ExplorationEvent event = ExplorationEventRegistry.randomByWeight(random);
        if (event == null) {
            return ExplorationResult.failure("暂无可用游历事件");
        }

        List<String> log = new ArrayList<>();
        log.add("你踏上了游历之路...");
        log.add("---");

        ExplorationResult result = new ExplorationResult();
        result.setSuccess(true);
        result.setLog(log);
        result.setNextExplorationTime(now + effectiveCd);

        event.execute(player, playerService, itemService, random, result, log);

        new DailyService().onExplore(player.getId(), result.getEventType());

        return result;
    }
}
