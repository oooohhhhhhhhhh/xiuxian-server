package com.mtxgdn.game.economy;

/**
 * 经济数据面板（运维/管理台用）。
 *
 * <p>{@code error} 在统计查询失败时才有值，此时其余字段为 null —— 面板会
 * 显示成"—"而不是 0，避免把"查不到"误读成"真的没有灵石"。</p>
 *
 * <p>{@code avgStonesPerPlayer} 是派生值（总量 / 人数，人数为 0 时是 0），
 * 放在这里算是因为面板只想要一个数，不该各自再除一次。</p>
 *
 * @param totalStones 全服灵石总量
 * @param totalGold 全服金币总量
 * @param totalPlayers 玩家数
 * @param avgStonesPerPlayer 人均灵石
 * @param last24hTradeVolume 24 小时内成交量
 * @param error 统计失败时的错误消息；正常时为 null
 */
public record EconomyStats(
        Long totalStones,
        Long totalGold,
        Long totalPlayers,
        Long avgStonesPerPlayer,
        Long last24hTradeVolume,
        String error) {

    public static EconomyStats of(
            long totalStones, long totalGold, long totalPlayers, long last24hTradeVolume) {
        long avg = totalPlayers > 0 ? totalStones / totalPlayers : 0;
        return new EconomyStats(totalStones, totalGold, totalPlayers, avg,
                last24hTradeVolume, null);
    }

    public static EconomyStats failed(String error) {
        return new EconomyStats(null, null, null, null, null, error);
    }
}
