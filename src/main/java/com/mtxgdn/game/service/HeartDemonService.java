package com.mtxgdn.game.service;

import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.SpiritualRoot;

import java.util.Random;

public class HeartDemonService {

    /**
     * 每小时基础心魔概率，<b>单位是百分点</b>（8.0 表示每小时 8%）。
     *
     * <p>这个单位必须和下面的抗性、上限保持一致 —— 历史上它是 {@code 0.08}
     * （当作"比例"），而抗性 {@code spiritResist/rootResist/caveResist} 与上限
     * {@code Math.min(40.0, ...)} 用的都是百分点。于是：
     * {@code finalChance = 0.08 - 0.67(灵力10) - 2.0(杂灵根) - ...} 恒为负，
     * 被 {@code Math.max(0.0, ...)} 夹到 0，{@code eventRoll >= 0} 恒成立 ——
     * <b>心魔从来不会触发</b>，整个心魔系统（含文案、修为倒退、各类抗性）都是死代码。</p>
     */
    private static final double BASE_CHANCE_PERCENT_PER_HOUR = 8.0;

    /** 单次结算的心魔概率上限（百分点）。 */
    private static final double MAX_CHANCE_PERCENT = 40.0;

    private static final Random RANDOM = new Random();

    private final PlayerService playerService = ServiceRegistry.getPlayerService();

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
        return processCultivationWithCave(playerId, rawExpGained, elapsedSeconds, 0);
    }

    public HeartDemonResult processCultivationWithCave(long playerId, long rawExpGained, int elapsedSeconds, int caveLevel) {
        HeartDemonResult result = new HeartDemonResult();
        result.rawExpGained = rawExpGained;

        if (rawExpGained <= 0 || elapsedSeconds <= 0) {
            result.netExpChange = rawExpGained;
            return result;
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.netExpChange = rawExpGained;
            return result;
        }

        // 全部按"百分点"计算：baseChancePercent 是 0~40 之间的百分数，
        // 各项抗性也是百分点，最后与 RANDOM.nextInt(100) 直接比较。
        double hours = elapsedSeconds / 3600.0;
        double baseChancePercent = BASE_CHANCE_PERCENT_PER_HOUR * hours;

        double spiritResist = Math.min(8.0, player.getSpirit() / 15.0);
        SpiritualRoot root = player.getSpiritualRoot();
        double rootResist = 0;
        if (root != null) {
            rootResist = switch (root.getTier()) {
                case PRIMORDIAL -> 8.0;
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

        double caveResist = caveLevel * 3.0;
        double finalChancePercent = Math.min(MAX_CHANCE_PERCENT,
                Math.max(0.0, baseChancePercent - spiritResist - rootResist - caveResist));

        int severityRoll = RANDOM.nextInt(100);
        int eventRoll = RANDOM.nextInt(100);

        result.roll = eventRoll;

        if (eventRoll >= finalChancePercent) {
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
