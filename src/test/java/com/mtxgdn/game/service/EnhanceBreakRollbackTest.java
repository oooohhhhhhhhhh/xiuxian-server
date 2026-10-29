package com.mtxgdn.game.service;

import com.mtxgdn.game.item.EquipResult;
import com.mtxgdn.game.item.EnhanceResult;
import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.item.ItemScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 强化碎裂必须把装备加过的属性一起收回。
 *
 * <p><b>缺陷形态</b>：{@code EnhanceService.unequipItem} 原来只有一条
 * {@code DELETE FROM players_equipment}。装备从身上消失了，但它通过
 * {@link com.mtxgdn.game.item.BuffEffect} 实打实加在
 * {@code players.attack/defense/speed/spirit} 上的属性从来没被扣回 ——
 * 玩家可以"强化到高等级 → 让它碎 → 再穿一件新的"反复白嫖属性，
 * 而且这份属性不在任何界面上标注来源，运维排查时完全看不出来。</p>
 *
 * <p>本测试用真实链路复现：装上一把带攻击加成的灵剑 → 记录攻击 → 强制碎裂 →
 * 断言攻击回到穿装备之前的值。碎裂结果是随机的，所以用反射把
 * {@code EnhanceService.random} 换成一个固定掷骰的 Random，
 * 让"碎裂"成为必然事件（用完必须换回去，注册表里那个实例是全局共享的）。</p>
 */
class EnhanceBreakRollbackTest extends TestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static final String SWORD = "mtxgdn:spirit_sword";

    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();
    private final EnhanceService enhanceService = ServiceRegistry.getEnhanceService();

    @BeforeAll
    static void registerItems() {
        ItemScanner.scanAndRegister();
    }

    @Test
    @DisplayName("装备碎裂后，装备带来的属性加成必须回退")
    void breakingEquipmentRollsBackItsStatBonus() throws Exception {
        int n = SEQ.incrementAndGet();
        long playerId = createPlayer("enhbreak" + n, "碎装者" + n);

        int attackBeforeEquip = playerService.getPlayerById(playerId).getAttack();

        assertTrue(itemService.addItem(playerId, SWORD, 1), "放入灵剑应成功");
        EquipResult equip = itemService.equipItem(playerId, SWORD, "weapon");
        assertEquals(Boolean.TRUE, equip.success(), "装备灵剑应成功: " + equip.message());

        int attackAfterEquip = playerService.getPlayerById(playerId).getAttack();
        assertTrue(attackAfterEquip > attackBeforeEquip,
                "灵剑必须带来攻击加成，否则本用例失去意义：before=" + attackBeforeEquip
                        + " after=" + attackAfterEquip);

        // 强化到 5 级（碎裂只从 5 级开始触发），并备好费用
        setEnhanceLevel(playerId, "weapon", 5);
        playerService.addGold(playerId, 100_000);
        assertTrue(itemService.addItem(playerId, "mtxgdn:enhance_stone", 10), "放入强化石应成功");

        // 5 级：成功率 65%、碎裂率 2%。掷 65 → 失败且 failRoll=0 < 2 → 必然碎裂
        EnhanceResult result = withFixedRoll(65, () -> enhanceService.enhanceItem(playerId, "weapon"));

        assertEquals(Boolean.TRUE, result.broken(),
                "本用例要求走到碎裂分支，实际结果: " + result);

        assertNull(itemService.getEquippedItem(playerId, "weapon"), "碎裂后装备槽必须为空");
        int attackAfterBreak = playerService.getPlayerById(playerId).getAttack();
        assertEquals(attackBeforeEquip, attackAfterBreak,
                "装备已销毁，它带来的 " + (attackAfterEquip - attackBeforeEquip)
                        + " 点攻击必须一起收回；否则玩家可以靠碎裂永久保留属性");
    }

    // ==================== 夹具 ====================

    /** 固定掷骰的 Random：让随机的强化结果变成可断言的事件。 */
    private static final class FixedRandom extends Random {
        private final int value;

        FixedRandom(int value) {
            this.value = value;
        }

        @Override
        public int nextInt(int bound) {
            return Math.min(value, bound - 1);
        }
    }

    private <T> T withFixedRoll(int roll, ThrowingSupplier<T> action) throws Exception {
        Field field = EnhanceService.class.getDeclaredField("random");
        field.setAccessible(true);
        Object original = field.get(enhanceService);
        field.set(enhanceService, new FixedRandom(roll));
        try {
            return action.get();
        } finally {
            field.set(enhanceService, original);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private long createPlayer(String username, String name) throws SQLException {
        long userId;
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (username, password) VALUES (?, ?)")) {
                ps.setString(1, username);
                ps.setString(2, "x");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    userId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players (user_id, name, spiritual_root, realm, sub_realm, "
                            + "hp, max_hp, mp, max_mp, attack, defense, speed, spirit, experience, "
                            + "gold, last_breakthrough_time) "
                            + "VALUES (?, ?, 'FIRE_WOOD_EARTH', 0, 0, 100, 100, 50, 50, 10, 10, 10, 10, 0, 0, 0)")) {
                ps.setLong(1, userId);
                ps.setString(2, name);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM players WHERE user_id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    return rs.getLong(1);
                }
            }
        }
    }

    private void setEnhanceLevel(long playerId, String slot, int level) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE players_equipment SET enhance_level = ? WHERE player_id = ? AND slot = ?")) {
            ps.setInt(1, level);
            ps.setLong(2, playerId);
            ps.setString(3, slot);
            assertEquals(1, ps.executeUpdate(), "强化等级应已写入（装备行必须存在）");
        }
    }
}
