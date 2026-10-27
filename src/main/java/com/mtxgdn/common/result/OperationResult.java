package com.mtxgdn.common.result;

/**
 * 「操作成功与否 + 一句给玩家看的提示」这类结果的统一类型。
 *
 * <p><b>它替换的是什么</b>：服务层大量方法原来返回
 * {@code Map<String, Object>}，里面固定两个键 —— {@code success}（Boolean）
 * 与 {@code message}（String）。代价很具体：</p>
 *
 * <ul>
 *   <li>键名是字符串，把 {@code success} 写成 {@code sucess}、把 Boolean 写成
 *       字符串 {@code "true"}，都只有运行时才炸；</li>
 *   <li>{@code get()} 返回 {@code Object}，调用方被迫写
 *       {@code Boolean.TRUE.equals(result.get("success"))} 这种绕圈子的判空，
 *       忘了判空就是一个 NPE；</li>
 *   <li>加字段时没有编译期约束，前端拿到的 JSON 字段也没有任何地方能查；</li>
 *   <li>改字段名只能全文 grep 字符串。</li>
 * </ul>
 *
 * <p><b>适用边界（重要）</b>：只有「键集合恰好是 success + message」的方法才用它。
 * 需要额外带数据的方法（要返回宗门、战报、仓库列表、结算明细的）<b>不要</b>硬塞进
 * 这个 record —— 那会逼着调用方继续 {@code get("battleLog")} 取字符串键，
 * 等于把问题搬了个位置。这类方法应当各自定义一个字段有类型的 record。
 * 现有清单见 {@code ResultMapDebtTest}，它同时是一道只许缩小、不许增长的棘轮。</p>
 *
 * <p><b>序列化</b>：record 的组件名决定 Gson 输出的 JSON 键，所以组件名必须
 * 逐字保持 {@code success} / {@code message}，否则前端拿到的字段就变了。</p>
 *
 * @param success 操作是否成功
 * @param message 给用户看的提示（已国际化，可直接展示）
 */
public record OperationResult(boolean success, String message) {

    /** 成功结果。 */
    public static OperationResult ok(String message) {
        return new OperationResult(true, message);
    }

    /** 失败结果。 */
    public static OperationResult fail(String message) {
        return new OperationResult(false, message);
    }

    /**
     * 兼容旧调用方写法：{@code Boolean.TRUE.equals(result.get("success"))}。
     *
     * <p>保留它是因为迁移过程中还有调用方按老习惯判成功；新代码直接用
     * {@link #success()} 即可，无需再套 {@code Boolean.TRUE.equals(...)}。</p>
     */
    public boolean isSuccess() {
        return success;
    }
}
