package com.mtxgdn.game.sect;

/**
 * 宗门操作的统一返回。
 *
 * <p><b>缺陷形态</b>：宗门模块的十几个操作方法原来一律返回
 * {@code Map<String, Object>}，里面固定两个键 ——
 * {@code success}（Boolean）和 {@code message}（String）。
 * 代价很具体：</p>
 *
 * <ul>
 *   <li>键名是字符串，把 {@code success} 写成 {@code sucess}、把 Boolean 写成
 *       字符串 {@code "true"}，都只有运行时才炸；</li>
 *   <li>调用方要写 {@code Boolean.TRUE.equals(result.get("success"))} 这种
 *       绕来绕去的判空，忘了判空就是一个 NPE；</li>
 *   <li>加字段时没有编译期约束，前端拿到的 JSON 字段也没有任何地方能查；</li>
 *   <li>改字段名只能全文 grep 字符串。</li>
 * </ul>
 *
 * <p>现在固定形状的结果用这个 record：两个字段有类型，调用方写
 * {@code result.success()} / {@code.message()}，拼错编译期就过不去。</p>
 *
 * <p><b>注意</b>：只有"键集合恰好是 success + message"的方法才用它。
 * 需要额外带数据的方法（比如要返回宗门列表、申请列表）仍然各自有专门的
 * 响应类型，不硬塞进这个 record。</p>
 *
 * @param success 操作是否成功
 * @param message 给用户看的提示（已国际化，直接可展示）
 */
public record SectOperationResult(boolean success, String message) {

    /** 兼容旧调用方写法：{@code Boolean.TRUE.equals(result.get("success"))}。 */
    public boolean isSuccess() {
        return success;
    }
}
