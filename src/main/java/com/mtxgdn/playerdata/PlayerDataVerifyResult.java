package com.mtxgdn.playerdata;

/**
 * 快照文件校验的结果。
 *
 * @param success 校验是否通过
 * @param message 给管理员看的提示
 * @param legacy 是否是老版本（无摘要）的快照；仅校验通过时有值
 * @param rowCount 快照行数；仅校验通过时有值
 * @param checksum 快照摘要；仅新版本快照校验通过时有值
 */
public record PlayerDataVerifyResult(
        boolean success,
        String message,
        Boolean legacy,
        Integer rowCount,
        String checksum) {

    public static PlayerDataVerifyResult fail(String message) {
        return new PlayerDataVerifyResult(false, message, null, null, null);
    }

    public static PlayerDataVerifyResult legacyOk(String message, int rowCount) {
        return new PlayerDataVerifyResult(true, message, true, rowCount, null);
    }

    public static PlayerDataVerifyResult verified(String message, int rowCount, String checksum) {
        return new PlayerDataVerifyResult(true, message, false, rowCount, checksum);
    }
}
