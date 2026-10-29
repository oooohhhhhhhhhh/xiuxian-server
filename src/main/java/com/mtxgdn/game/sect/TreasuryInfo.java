package com.mtxgdn.game.sect;

import com.mtxgdn.game.entity.Sect;
import java.util.List;

/**
 * 金库与工资面板。
 *
 * @param success 是否查询成功
 * @param message 面板正文（多行文本，直接发给玩家）
 * @param sect 宗门实体；失败时为 null
 * @param members 每个成员的应发工资；失败时为 null
 * @param periodCost 本期总支出；失败时为 null
 * @param nextPayTime 下次发放时间戳（毫秒）；失败时为 null
 */
public record TreasuryInfo(
        boolean success,
        String message,
        Sect sect,
        List<SalaryRow> members,
        Long periodCost,
        Long nextPayTime) {

    public static TreasuryInfo fail(String message) {
        return new TreasuryInfo(false, message, null, null, null, null);
    }

    public static TreasuryInfo of(String message, Sect sect, List<SalaryRow> members,
                                  long periodCost, long nextPayTime) {
        return new TreasuryInfo(true, message, sect, members, periodCost, nextPayTime);
    }
}
