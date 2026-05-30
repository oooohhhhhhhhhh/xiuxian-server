package com.mtxgdn.game.item;

import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

public class HealEffect extends ItemEffect {

    private int hpRestore;
    private int mpRestore;

    public HealEffect() {
    }

    public HealEffect(int hpRestore, int mpRestore) {
        this.hpRestore = hpRestore;
        this.mpRestore = mpRestore;
    }

    public int getHpRestore() {
        return hpRestore;
    }

    public void setHpRestore(int hpRestore) {
        this.hpRestore = hpRestore;
    }

    public int getMpRestore() {
        return mpRestore;
    }

    public void setMpRestore(int mpRestore) {
        this.mpRestore = mpRestore;
    }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        StringBuilder sb = new StringBuilder();
        if (hpRestore > 0) {
            playerService.addHp(playerId, hpRestore);
            sb.append("恢复了 ").append(hpRestore).append(" 点生命值，");
        }
        if (mpRestore > 0) {
            playerService.addMp(playerId, mpRestore);
            sb.append("恢复了 ").append(mpRestore).append(" 点法力值，");
        }
        return sb.toString();
    }

    public static HealEffect hp(int hp) {
        return new HealEffect(hp, 0);
    }

    public static HealEffect mp(int mp) {
        return new HealEffect(0, mp);
    }

    public static HealEffect of(int hp, int mp) {
        return new HealEffect(hp, mp);
    }
}
