package com.mtxgdn.game.economy;

/**
 * 单笔灵庄存款的展示信息。
 *
 * @param id 存款单号
 * @param type 存款类型显示名（"活期" / "N天定期"）
 * @param principal 本金
 * @param estimatedInterest 预计利息（活期为累计估算，定期为到期可得）
 * @param note 说明文本（已存天数 / 剩余天数 / 已到期）
 */
public record DepositView(
        long id,
        String type,
        long principal,
        long estimatedInterest,
        String note) {
}
