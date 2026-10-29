package com.mtxgdn.playerdata;

/**
 * 单个玩家数据导出/还原的结果。
 *
 * <p>导出与还原共用一条成功提示通道，但携带的字段不同，因此用可空字段区分：
 * 导出成功带 {@code filePath}/{@code exportedAt}/{@code rowCount}/{@code checksum}，
 * 还原成功只带 {@code restored}（实际写回的表格数）。</p>
 *
 * @param success 是否成功
 * @param message 给管理员/玩家看的提示
 * @param filePath 快照文件路径；仅导出成功时有值
 * @param exportedAt 导出时间戳（毫秒）；仅导出成功时有值
 * @param rowCount 快照行数；仅导出成功时有值
 * @param checksum 快照摘要；仅导出成功时有值
 * @param restored 实际还原的表格数；仅还原成功时有值
 */
public record PlayerDataResult(
        boolean success,
        String message,
        String filePath,
        Long exportedAt,
        Integer rowCount,
        String checksum,
        Integer restored) {

    public static PlayerDataResult fail(String message) {
        return new PlayerDataResult(false, message, null, null, null, null, null);
    }

    public static PlayerDataResult exported(String message, String filePath,
                                            long exportedAt, int rowCount, String checksum) {
        return new PlayerDataResult(true, message, filePath, exportedAt, rowCount, checksum, null);
    }

    public static PlayerDataResult restored(String message, int restored) {
        return new PlayerDataResult(true, message, null, null, null, null, restored);
    }
}
