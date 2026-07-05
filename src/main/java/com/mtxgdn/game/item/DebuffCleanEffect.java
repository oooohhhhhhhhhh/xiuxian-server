package com.mtxgdn.game.item;

import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

public class DebuffCleanEffect extends ItemEffect {

    private boolean removeAllDebuffs;

    public DebuffCleanEffect() {
        this.removeAllDebuffs = true;
    }

    public DebuffCleanEffect(boolean removeAllDebuffs) {
        this.removeAllDebuffs = removeAllDebuffs;
    }

    public boolean isRemoveAllDebuffs() {
        return removeAllDebuffs;
    }

    public void setRemoveAllDebuffs(boolean removeAllDebuffs) {
        this.removeAllDebuffs = removeAllDebuffs;
    }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        return "使用净化丹药，清除所有负面状态";
    }

    public static DebuffCleanEffect of(boolean removeAll) {
        return new DebuffCleanEffect(removeAll);
    }

    public static DebuffCleanEffect all() {
        return new DebuffCleanEffect(true);
    }
}