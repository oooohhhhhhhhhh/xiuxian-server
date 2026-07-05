package com.mtxgdn.game.item;

import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

public class RebirthEffect extends ItemEffect {

    private boolean fullRestore;

    public RebirthEffect() {
        this.fullRestore = true;
    }

    public RebirthEffect(boolean fullRestore) {
        this.fullRestore = fullRestore;
    }

    public boolean isFullRestore() {
        return fullRestore;
    }

    public void setFullRestore(boolean fullRestore) {
        this.fullRestore = fullRestore;
    }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        if (fullRestore) {
            playerService.addHp(playerId, Integer.MAX_VALUE);
            playerService.addMp(playerId, Integer.MAX_VALUE);
            return "原地复活！生命值和法力值已完全恢复";
        } else {
            playerService.addHp(playerId, 50);
            playerService.addMp(playerId, 25);
            return "复活！恢复了部分生命值和法力值";
        }
    }

    public static RebirthEffect full() {
        return new RebirthEffect(true);
    }

    public static RebirthEffect partial() {
        return new RebirthEffect(false);
    }
}