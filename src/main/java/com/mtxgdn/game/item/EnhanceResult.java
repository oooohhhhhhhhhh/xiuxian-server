package com.mtxgdn.game.item;

/**
 * 装备强化的结果。
 *
 * <p>这个结果形状比较杂，原因在业务本身：强化分五种结局（成功 / 碎裂 /
 * 被保护符挡下 / 掉级 / 单纯失败），每种带的标志位不同。HTTP 与 WebSocket
 * 入口会把整个结果序列化给前端，因此字段名必须与改造前的 Map 逐字一致。</p>
 *
 * <p>用可空包装（{@code Integer} / {@code Boolean}）而不是基本类型：
 * Gson 默认跳过 null 字段，这样「该分支没有这个字段」与「字段值为 0/false」
 * 才能保持原来的区别。比如碎裂时没有 {@code newLevel}，写成 0 会凭空多出
 * 一个 {@code "newLevel":0}。</p>
 *
 * @param success 操作是否成功（强化失败时也是 false，但 message 会说明原因）
 * @param message 给玩家看的提示
 * @param slot 装备栏位；校验未通过时为 null
 * @param itemName 装备名；校验未通过时为 null
 * @param currentLevel 强化前等级；校验未通过时为 null
 * @param costGold 消耗金币；校验未通过时为 null
 * @param costStones 消耗强化石；校验未通过时为 null
 * @param successRate 本次成功率（含保底加成）
 * @param breakRate 本次碎裂率
 * @param pityStreak 当前连续失败次数
 * @param enhanceSuccess 是否强化成功；校验未通过时为 null
 * @param newLevel 强化后的等级；仅成功与掉级两个分支有值
 * @param broken 是否碎裂
 * @param brokenItem 碎裂的装备名
 * @param protect 是否被保护符挡下
 * @param degraded 是否掉级
 */
public record EnhanceResult(
        boolean success,
        String message,
        String slot,
        String itemName,
        Integer currentLevel,
        Integer costGold,
        Integer costStones,
        Integer successRate,
        Integer breakRate,
        Integer pityStreak,
        Boolean enhanceSuccess,
        Integer newLevel,
        Boolean broken,
        String brokenItem,
        Boolean protect,
        Boolean degraded) {

    /** 前置校验未通过：只有 success 与 message，与改造前的 Map 形状一致。 */
    public static EnhanceResult rejected(String message) {
        return builder().success(false).message(message).build();
    }

    /** 事务抛异常：明确带上 enhanceSuccess=false，前端据此区分「没强化」与「强化炸了」。 */
    public static EnhanceResult failed(String message) {
        return builder().success(false).enhanceSuccess(false).message(message).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 结局字段太多，用 builder 比十六个位置参数可读。 */
    public static final class Builder {
        private boolean success;
        private String message;
        private String slot;
        private String itemName;
        private Integer currentLevel;
        private Integer costGold;
        private Integer costStones;
        private Integer successRate;
        private Integer breakRate;
        private Integer pityStreak;
        private Boolean enhanceSuccess;
        private Integer newLevel;
        private Boolean broken;
        private String brokenItem;
        private Boolean protect;
        private Boolean degraded;

        public Builder success(boolean v) { this.success = v; return this; }
        public Builder message(String v) { this.message = v; return this; }
        public Builder slot(String v) { this.slot = v; return this; }
        public Builder itemName(String v) { this.itemName = v; return this; }
        public Builder currentLevel(Integer v) { this.currentLevel = v; return this; }
        public Builder costGold(Integer v) { this.costGold = v; return this; }
        public Builder costStones(Integer v) { this.costStones = v; return this; }
        public Builder successRate(Integer v) { this.successRate = v; return this; }
        public Builder breakRate(Integer v) { this.breakRate = v; return this; }
        public Builder pityStreak(Integer v) { this.pityStreak = v; return this; }
        public Builder enhanceSuccess(Boolean v) { this.enhanceSuccess = v; return this; }
        public Builder newLevel(Integer v) { this.newLevel = v; return this; }
        public Builder broken(Boolean v) { this.broken = v; return this; }
        public Builder brokenItem(String v) { this.brokenItem = v; return this; }
        public Builder protect(Boolean v) { this.protect = v; return this; }
        public Builder degraded(Boolean v) { this.degraded = v; return this; }

        public EnhanceResult build() {
            return new EnhanceResult(success, message, slot, itemName, currentLevel,
                    costGold, costStones, successRate, breakRate, pityStreak,
                    enhanceSuccess, newLevel, broken, brokenItem, protect, degraded);
        }
    }
}
