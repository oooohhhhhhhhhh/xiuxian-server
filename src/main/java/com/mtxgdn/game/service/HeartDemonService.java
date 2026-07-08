package com.mtxgdn.game.service;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.SpiritualRoot;

import java.util.Random;

public class HeartDemonService {

    private static final double BASE_CHANCE_PER_HOUR = 0.08;
    private static final Random RANDOM = new Random();

    private final PlayerService playerService = new PlayerService();

    public static class HeartDemonResult {
        public boolean triggered;
        public String severity;
        public String narrative;
        public long rawExpGained;
        public long netExpChange;
        public long expLost;
        public double roll;
    }

    public HeartDemonResult processCultivation(long playerId, long rawExpGained, int elapsedSeconds) {
        HeartDemonResult result = new HeartDemonResult();
        result.rawExpGained = rawExpGained;

        if (rawExpGained <= 0 || elapsedSeconds <= 0) {
            result.netExpChange = rawExpGained;
            return result;
        }

        Player player = playerService.getPlayerRaw(playerId);
        if (player == null) {
            result.netExpChange = rawExpGained;
            return result;
        }

        double hours = elapsedSeconds / 3600.0;
        double baseChance = BASE_CHANCE_PER_HOUR * hours;

        double spiritResist = Math.min(8.0, player.getSpirit() / 15.0);
        SpiritualRoot root = player.getSpiritualRoot();
        double rootResist = 0;
        if (root != null) {
            rootResist = switch (root.getTier()) {
                case PERFECT -> 6.0;
                case VARIANT -> 4.0;
                case HEAVENLY -> 3.0;
                case DUAL -> 2.0;
                case TRIPLE -> 1.0;
                case QUAD -> 0;
                case MIXED -> -2.0;
            };
            if (root.hasEffect(SpiritualRoot.SpecialEffect.LATE_BLOOMER)) {
                rootResist += 2.0;
            }
        }

        double finalChance = Math.min(40.0, baseChance - spiritResist - rootResist);

        int severityRoll = RANDOM.nextInt(100);
        int eventRoll = RANDOM.nextInt(100);

        result.roll = eventRoll;

        if (eventRoll >= finalChance) {
            result.netExpChange = rawExpGained;
            return result;
        }

        result.triggered = true;

        String severity;
        double lossRatio;
        String narrative;

        if (severityRoll < 50) {
            severity = "轻微";
            lossRatio = 0.2 + RANDOM.nextDouble() * 0.2;
            int idx = RANDOM.nextInt(MINOR_NARRATIVES.length);
            narrative = MINOR_NARRATIVES[idx];
        } else if (severityRoll < 85) {
            severity = "中等";
            lossRatio = 0.50 + RANDOM.nextDouble() * 0.20;
            int idx = RANDOM.nextInt(MEDIUM_NARRATIVES.length);
            narrative = MEDIUM_NARRATIVES[idx];
        } else {
            severity = "严重";
            lossRatio = 0.80 + RANDOM.nextDouble() * 0.20;
            int idx = RANDOM.nextInt(SEVERE_NARRATIVES.length);
            narrative = SEVERE_NARRATIVES[idx];
        }

        long expLost = (long)(rawExpGained * lossRatio);
        if (expLost < 1) expLost = 1;
        long netExp = rawExpGained - expLost;

        result.severity = severity;
        result.expLost = expLost;
        result.netExpChange = netExp;
        result.narrative = narrative + "（修为倒退 " + expLost + " 点）";

        return result;
    }

    private static final String[] MINOR_NARRATIVES = {
        "修炼中杂念丛生，灵力略有溃散...",
        "一丝心魔悄然滋生，扰乱了你的吐纳节奏...",
        "眼前忽现过往执念，一时心神失守，修为微损...",
        "丹田之中灵力一阵翻涌，险些岔气走偏...",
        "修炼中打了个盹，醒来发现真气散了大半...",
    };

    private static final String[] MEDIUM_NARRATIVES = {
        "心魔趁虚而入！脑海中万般杂念翻涌，真气逆行...",
        "一道黑气自丹田蔓延而出，你越是抵抗越是痛苦...",
        "眼前幻象重重，分不清现实与幻觉，修为大乱...",
        "修炼进入关键时，忽闻耳边低语——那是最深处的恐惧...",
    };

    private static final String[] SEVERE_NARRATIVES = {
        "走火入魔！灵力在经脉中暴走，你痛苦地抽搐着倒地...",
        "心魔化形，竟是你自己的模样！它冷笑着一掌拍来...",
        "修炼中触动了道心最脆弱的角落，灵力如决堤之水般倾泻...",
        "天旋地转，你发现自己站在一片虚无之中，修为正在疯狂流逝...",
    };
}
