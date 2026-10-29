package com.mtxgdn.game.sect;

/**
 * 单个成员的应发工资。
 *
 * @param playerId 玩家 ID
 * @param name 玩家名字
 * @param role 职位显示名
 * @param expected 应发金额
 */
public record SalaryRow(
        long playerId,
        String name,
        String role,
        long expected) {
}
