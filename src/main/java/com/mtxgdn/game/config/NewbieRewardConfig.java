package com.mtxgdn.game.config;

import java.util.ArrayList;
import java.util.List;

public class NewbieRewardConfig {

    private boolean enabled = false;
    private long goldReward = 0;
    private long spiritStoneReward = 0;
    private int spiritStoneGrade = 0;
    private List<RewardItem> items = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getGoldReward() {
        return goldReward;
    }

    public void setGoldReward(long goldReward) {
        this.goldReward = goldReward;
    }

    public long getSpiritStoneReward() {
        return spiritStoneReward;
    }

    public void setSpiritStoneReward(long spiritStoneReward) {
        this.spiritStoneReward = spiritStoneReward;
    }

    public int getSpiritStoneGrade() {
        return spiritStoneGrade;
    }

    public void setSpiritStoneGrade(int spiritStoneGrade) {
        this.spiritStoneGrade = spiritStoneGrade;
    }

    public List<RewardItem> getItems() {
        return items;
    }

    public void setItems(List<RewardItem> items) {
        this.items = items;
    }

    public static class RewardItem {
        private String itemKey;
        private long quantity;

        public RewardItem() {}

        public RewardItem(String itemKey, long quantity) {
            this.itemKey = itemKey;
            this.quantity = quantity;
        }

        public String getItemKey() {
            return itemKey;
        }

        public void setItemKey(String itemKey) {
            this.itemKey = itemKey;
        }

        public long getQuantity() {
            return quantity;
        }

        public void setQuantity(long quantity) {
            this.quantity = quantity;
        }
    }
}