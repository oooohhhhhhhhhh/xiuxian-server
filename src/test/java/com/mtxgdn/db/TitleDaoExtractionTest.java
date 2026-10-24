package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 称号模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code TitleService} 原来 259 行里 6 处
 * {@code DatabaseManager.getConnection()}，而装备称号是"卸下当前 → 装备目标"
 * 两步事务，原实现靠 {@code runTransaction} 把同一条 {@code conn}
 * 传给 {@code unequipCurrentTitle} 与 {@code equipNewTitle} 两个 private 方法。
 * 这个约定只存在于作者脑子里，抽 DAO 时一旦让两步各自自取连接，
 * 中间失败就会留下"一个都没装备"或"两个都装备"。</p>
 */
class TitleDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return TitleDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/TitleService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/TitleDAO.java";
    }

    @Override
    protected java.util.List<String> serviceAnchors() {
        return java.util.List.of(
                "TitleRegistry.get",
                "TitleRegistry.init",
                "checkAndGrantTitles",
                "grantIf",
                "buildTitleMap",
                "getSpiritStoneCount");
    }

    @Test
    @DisplayName("四条 player_titles 语句逐字搬进 DAO，条件列没被改")
    void titleSqlIsVerbatim() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("SELECT title_key, is_equipped, acquired_at FROM player_titles "
                        + "WHERE player_id = ? ORDER BY acquired_at DESC"),
                "列表查询的列清单与倒序不能丢");
        assertTrue(code.contains("SELECT title_key FROM player_titles WHERE player_id = ? AND is_equipped = ?"),
                "取当前装备称号的条件不能丢");
        assertTrue(code.contains("SELECT 1 FROM player_titles WHERE player_id = ? AND title_key = ?"),
                "查是否已拥有的语句不能丢");
        assertTrue(code.contains("INSERT INTO player_titles (player_id, title_key, is_equipped) "
                        + "VALUES (?, ?, ?)"),
                "授予语句的列清单不能丢");
        assertTrue(code.contains("DELETE FROM player_titles WHERE player_id = ? AND title_key = ?"),
                "撤销语句不能丢");
        // 卸下与装备是两条不同的条件更新
        assertTrue(code.contains("UPDATE player_titles SET is_equipped = ? WHERE player_id = ? "
                        + "AND is_equipped = ?"),
                "卸下按 is_equipped = true 定位，不是按 title_key");
        assertTrue(code.contains("UPDATE player_titles SET is_equipped = ? WHERE player_id = ? "
                        + "AND title_key = ?"),
                "装备按 title_key 定位");
    }

    @Test
    @DisplayName("授予时 is_equipped 固定写 false，绝不顶掉当前装备")
    void grantNeverEquips() throws IOException {
        String code = stripComments(read(daoPath()));
        assertEquals(2, countOccurrences(code, "setBoolean(3, false)"),
                "自取连接版与 Connection 版的授予都必须写 is_equipped = false");
    }

    @Test
    @DisplayName("装备称号的两步必须共用同一条连接")
    void equipSharesOneConnection() throws IOException {
        String service = stripComments(read(servicePath()));
        int start = service.indexOf("public Map<String, Object> equipTitle(");
        assertTrue(start >= 0, "找不到 equipTitle 方法");
        String body = service.substring(start, service.indexOf("public Map<String, Object> unequipTitle(", start));
        assertTrue(body.contains("TITLE_DAO.unequipAll(conn, playerId)"),
                "卸下当前装备必须用调用方的 conn");
        assertTrue(body.contains("TITLE_DAO.equipTitle(conn, playerId, titleKey)"),
                "装备目标必须用同一条 conn，否则中间失败会留下"
                        + "「一个都没装备」或「新旧两个都装备」的状态");
        // 顺序不能反：先卸后装
        assertTrue(body.indexOf("TITLE_DAO.unequipAll(conn,") < body.indexOf("TITLE_DAO.equipTitle(conn,"),
                "必须先卸下再装备，否则刚装上的那行会被自己卸掉");
    }

    @Test
    @DisplayName("卸下返回的行数仍是「已卸下 / 没有装备」文案的分支依据")
    void unequipRowCountDrivesMessage() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("rows > 0"),
                "unequipAll 的受影响行数是业务分支：0 行要说"
                        + "「当前没有装备中的称号」而不是「已卸下」");
        assertTrue(service.contains("title.unequipped"), "有装备时的文案不能丢");
        assertTrue(service.contains("title.none_equipped"), "没有装备时的文案不能丢");
    }

    @Test
    @DisplayName("注册表查询与「查不到就跳过该行」的过滤留在服务层")
    void registryLookupStaysInService() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("if (title == null) continue;"),
                "注册表里没有的脏 key 必须跳过，不能让整份列表失败");
        assertTrue(service.contains("title != null ? title.getName() : titleKey"),
                "撤销时查不到称号要退回原 key，不能编一个名字");
        // DAO 不该碰注册表
        String dao = stripComments(read(daoPath()));
        assertFalse(dao.contains("TitleRegistry"), "DAO 不该查 TitleRegistry 静态注册表");
        assertFalse(dao.contains("I18n"), "DAO 不该做文案组装");
    }

    @Test
    @DisplayName("DAO 不碰事务与缓存失效")
    void daoStaysPurePersistence() throws IOException {
        String code = stripComments(read(daoPath()));
        for (String token : java.util.List.of("markDirty", "runTransaction", "setAutoCommit",
                "TitleRegistry", "I18n")) {
            assertFalse(code.contains(token), "DAO 不该出现 " + token);
        }
    }
}
