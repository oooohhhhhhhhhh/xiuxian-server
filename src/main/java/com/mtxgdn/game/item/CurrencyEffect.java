package com.mtxgdn.game.item;

import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

public class CurrencyEffect extends ItemEffect {

    public static final String SPIRIT_STONE_KEY = "mtxgdn:spirit_stone_low";
    public static final String SPIRIT_STONE_LOW = "mtxgdn:spirit_stone_low";
    public static final String SPIRIT_STONE_MID = "mtxgdn:spirit_stone_mid";
    public static final String SPIRIT_STONE_HIGH = "mtxgdn:spirit_stone_high";
    public static final String SPIRIT_STONE_SUPREME = "mtxgdn:spirit_stone_supreme";
    public static final String SPIRIT_STONE_OLD_KEY = "mtxgdn:spirit_stone";

    public static final long[] EXCHANGE_RATES = {1, 1000, 1000000, 1000000000L};
    public static final double[] AURA_MULTIPLIERS = {1.0, 2.0, 3.0, 5.0};

    /** 每个玩家可持有的各等级灵石硬上限，0表示无限制 */
    public static final long[] MAX_HOLD_PER_GRADE = {0, 999999, 9999, 99};

    /** 各等级灵石合并阈值（以下品等值计），超过此值自动向上一级合并 */
    public static final long[] CONSOLIDATE_THRESHOLD_LOW = {2000, 2000000, 2000000000L, 0};

    /** 灵石等级名称 */
    public static final String[] GRADE_NAMES = {"下品灵石", "中品灵石", "上品灵石", "极品灵石"};

    private long goldGain;
    private long spiritStoneGain;
    private int stoneGrade;

    public CurrencyEffect() {
        this.stoneGrade = 0;
    }

    public CurrencyEffect(long goldGain, long spiritStoneGain) {
        this.goldGain = goldGain;
        this.spiritStoneGain = spiritStoneGain;
        this.stoneGrade = 0;
    }

    public CurrencyEffect(long goldGain, long spiritStoneGain, int stoneGrade) {
        this.goldGain = goldGain;
        this.spiritStoneGain = spiritStoneGain;
        this.stoneGrade = stoneGrade;
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

    public int getStoneGrade() {
        return stoneGrade;
    }

    public void setStoneGrade(int stoneGrade) {
        this.stoneGrade = stoneGrade;
    }

    public static String getStoneKey(int grade) {
        return switch (grade) {
            case 1 -> SPIRIT_STONE_MID;
            case 2 -> SPIRIT_STONE_HIGH;
            case 3 -> SPIRIT_STONE_SUPREME;
            default -> SPIRIT_STONE_LOW;
        };
    }

    public static long getExchangeRate(int grade) {
        if (grade < 0 || grade >= EXCHANGE_RATES.length) return 1;
        return EXCHANGE_RATES[grade];
    }

    public static double getAuraMultiplier(int grade) {
        if (grade < 0 || grade >= AURA_MULTIPLIERS.length) return 1.0;
        return AURA_MULTIPLIERS[grade];
    }

    public static long convertToLowGrade(long amount, int fromGrade) {
        return amount * getExchangeRate(fromGrade);
    }

    public static long convertFromLowGrade(long lowAmount, int toGrade) {
        return lowAmount / getExchangeRate(toGrade);
    }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        StringBuilder sb = new StringBuilder();
        if (goldGain > 0) {
            playerService.addGold(playerId, goldGain);
            sb.append("获得了 ").append(goldGain).append(" 金币，");
        }
        if (spiritStoneGain > 0) {
            String stoneKey = getStoneKey(stoneGrade);
            itemService.addItem(playerId, stoneKey, spiritStoneGain);
            String stoneName = getStoneName(stoneGrade);
            sb.append("获得了 ").append(spiritStoneGain).append(" ").append(stoneName).append("，");
        }
        return sb.toString();
    }

    private String getStoneName(int grade) {
        return switch (grade) {
            case 1 -> "中品灵石";
            case 2 -> "上品灵石";
            case 3 -> "极品灵石";
            default -> "下品灵石";
        };
    }

    public static CurrencyEffect of(long gold, long spiritStones) {
        return new CurrencyEffect(gold, spiritStones, 0);
    }

    public static CurrencyEffect of(long gold, long spiritStones, int stoneGrade) {
        return new CurrencyEffect(gold, spiritStones, stoneGrade);
    }
}