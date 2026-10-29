package com.mtxgdn.playerdata;

/**
 * 导出/还原的运行统计，供管理后台展示。
 *
 * @param enabled 功能是否启用
 * @param dir 快照目录绝对路径
 * @param exportOk 累计导出成功次数
 * @param exportFail 累计导出失败次数
 * @param restoreOk 累计还原成功次数
 * @param restoreFail 累计还原失败次数
 * @param pendingDirty 待落盘的脏玩家数
 * @param lastError 最近一次错误消息；从未出错时为 null
 * @param lastErrorAt 最近一次错误时间戳（毫秒）；从未出错时为 0
 */
public record PlayerDataStats(
        boolean enabled,
        String dir,
        long exportOk,
        long exportFail,
        long restoreOk,
        long restoreFail,
        int pendingDirty,
        String lastError,
        long lastErrorAt) {
}
