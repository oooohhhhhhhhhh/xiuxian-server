package com.mtxgdn.game.formation;

/**
 * 阵法升级结果。
 *
 * <p>除了统一的 {@code success} / {@code message}，HTTP 入口还需要把升级后的
 * 等级返回给前端，因此不能只用 {@code OperationResult}。</p>
 *
 * @param success 是否升级成功
 * @param message 给玩家看的提示
 * @param newLevel 升级后的等级；失败时为 0，调用方只在成功时输出该字段
 */
public record FormationUpgradeResult(boolean success, String message, int newLevel) {

    public static FormationUpgradeResult ok(String message, int newLevel) {
        return new FormationUpgradeResult(true, message, newLevel);
    }

    public static FormationUpgradeResult fail(String message) {
        return new FormationUpgradeResult(false, message, 0);
    }
}
