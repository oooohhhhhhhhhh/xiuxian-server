package com.mtxgdn.game.entity;

import java.util.List;

public class RealmConfigFile {

    private List<RealmConfig> realms;
    private CultivationPerSecond cultivationPerSecond;

    public RealmConfigFile() {
    }

    public List<RealmConfig> getRealms() {
        return realms;
    }

    public void setRealms(List<RealmConfig> realms) {
        this.realms = realms;
    }

    public CultivationPerSecond getCultivationPerSecond() {
        return cultivationPerSecond;
    }

    public void setCultivationPerSecond(CultivationPerSecond cultivationPerSecond) {
        this.cultivationPerSecond = cultivationPerSecond;
    }

    public static class CultivationPerSecond {
        private int baseValue;
        private double[] realmMultiplier;

        public int getBaseValue() {
            return baseValue;
        }

        public void setBaseValue(int baseValue) {
            this.baseValue = baseValue;
        }

        public double[] getRealmMultiplier() {
            return realmMultiplier;
        }

        public void setRealmMultiplier(double[] realmMultiplier) {
            this.realmMultiplier = realmMultiplier;
        }
    }
}
