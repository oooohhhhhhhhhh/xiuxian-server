package com.mtxgdn.game.sect;

/**
 * 宗主退位的结果。
 *
 * @param success 是否退位成功
 * @param message 给玩家看的提示
 * @param successorId 继任者玩家 ID；失败时为 null
 * @param successorName 继任者名字；失败时为 null
 * @param contestUntil 竞争期截止时间戳（毫秒），0 表示未开启；失败时为 null
 */
public record StepDownResult(
        boolean success,
        String message,
        Long successorId,
        String successorName,
        Long contestUntil) {

    public static StepDownResult fail(String message) {
        return new StepDownResult(false, message, null, null, null);
    }

    public static StepDownResult steppedDown(
            String message, long successorId, String successorName, long contestUntil) {
        return new StepDownResult(true, message, successorId, successorName, contestUntil);
    }
}
