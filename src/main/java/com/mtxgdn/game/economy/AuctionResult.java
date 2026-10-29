package com.mtxgdn.game.economy;

/**
 * 竞拍操作（上架 / 出价）的结果。
 *
 * <p>两个操作共用同一形状：只有 {@code success} 与 {@code message}。
 * 上架不返回挂单号 —— DAO 的 {@code insertAuctionListing} 是 void，
 * 原实现也从未往结果里放过这个键，不要在这里凭空加一个。</p>
 *
 * @param success 是否操作成功
 * @param message 给玩家看的提示
 */
public record AuctionResult(boolean success, String message) {

    public static AuctionResult ok(String message) {
        return new AuctionResult(true, message);
    }

    public static AuctionResult fail(String message) {
        return new AuctionResult(false, message);
    }
}
