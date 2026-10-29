package com.mtxgdn.game.economy;

import java.util.List;

/**
 * 灵庄存款列表。
 *
 * @param deposits 每笔存款的展示信息
 * @param totalPrincipal 本金合计
 * @param totalPendingInterest 待收利息合计
 */
public record BankInfo(
        List<DepositView> deposits,
        long totalPrincipal,
        long totalPendingInterest) {
}
