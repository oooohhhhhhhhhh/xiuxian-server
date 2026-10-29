package com.mtxgdn.game.economy;

/**
 * 灵石提纯的结果。
 *
 * <p>注意 {@code success} 表示"提纯流程跑完了"，{@code purifySuccess} 才是
 * "这次提纯有没有成功"。两者分开是必要的：流程成功但提纯失败时仍要告诉
 * 玩家消耗了多少、成功率多少。</p>
 *
 * @param success 流程是否完成
 * @param message 给玩家看的提示
 * @param purifySuccess 本次提纯是否成功；流程失败时为 null
 * @param grade 源等级；流程失败时为 null
 * @param targetGrade 目标等级；流程失败时为 null
 * @param sourceName 源灵石名；流程失败时为 null
 * @param targetName 目标灵石名；流程失败时为 null
 * @param auraCost 消耗的洞府灵气；流程失败时为 null
 * @param stoneCost 消耗的灵石块数；流程失败时为 null
 * @param successRate 成功率 0..1；流程失败时为 null
 */
public record PurifyResult(
        boolean success,
        String message,
        Boolean purifySuccess,
        Integer grade,
        Integer targetGrade,
        String sourceName,
        String targetName,
        Long auraCost,
        Long stoneCost,
        Double successRate) {

    public static PurifyResult rejected(String message) {
        return new PurifyResult(false, message, null, null, null, null, null, null, null, null);
    }

    public static PurifyResult purified(
            String message, boolean purifySuccess, int grade, int targetGrade,
            String sourceName, String targetName, long auraCost, long stoneCost,
            double successRate) {
        return new PurifyResult(true, message, purifySuccess, grade, targetGrade,
                sourceName, targetName, auraCost, stoneCost, successRate);
    }
}
