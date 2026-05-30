package com.mtxgdn.game.item;

import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

public class CurrencyEffect extends ItemEffect {

    public static final String SPIRIT_STONE_KEY = "mtxgdn:spirit_stone";

    private long goldGain;
    private long spiritStoneGain;

    public CurrencyEffect() {
    }

    public CurrencyEffect(long goldGain, long spiritStoneGain) {
        this.goldGain = goldGain;
        this.spiritStoneGain = spiritStoneGain;
    }

    public long getGoldGain() {
        return goldGain;
    }

    public void setGoldGain(long goldGain) {
        this.goldGain = goldGain;
    }

    public long getSpiritStoneGain() {
        return spiritStoneGain;
    }

    public void setSpiritStoneGain(long spiritStoneGain) {
        this.spiritStoneGain = spiritStoneGain;
    }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        StringBuilder sb = new StringBuilder();
        if (goldGain > 0) {
            playerService.addGold(playerId, goldGain);
            sb.append("获得了 ").append(goldGain).append(" 金币，");
        }
        if (spiritStoneGain > 0) {
            itemService.addItem(playerId, SPIRIT_STONE_KEY, (int) spiritStoneGain);
            sb.append("获得了 ").append(spiritStoneGain).append(" 灵石，");
        }
        return sb.toString();
    }

    public static CurrencyEffect of(long gold, long spiritStones) {
        return new CurrencyEffect(gold, spiritStones);
    }
}
