package com.mtxgdn.playerdata;

/**
 * 全量导出的结果（迁移 / 整库备份用）。
 *
 * @param success 是否跑完（单个玩家失败不影响整体成功）
 * @param exported 成功导出的玩家数
 * @param failed 失败的玩家数
 * @param dir 快照目录的绝对路径
 */
public record PlayerDataExportAllResult(
        boolean success,
        int exported,
        int failed,
        String dir) {
}
