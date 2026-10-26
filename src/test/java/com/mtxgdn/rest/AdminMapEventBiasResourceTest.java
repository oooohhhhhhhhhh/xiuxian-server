package com.mtxgdn.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 管理台 · 地图事件权重偏置接口的行为测试。
 *
 * <p>端点的存在性与权限码由 {@code EndpointAlignmentTest} 用反射锁定，这里验证行为。
 * 重点钉住三件抽取 JDBC 时容易丢掉的事：</p>
 * <ul>
 *   <li><b>先删后插必须是同一条连接上的两步</b>：抽成 DAO 之后，如果有人在事务外
 *       只调 delete、忘了 insert，或者调了两个各自开连接的方法，
 *       表现就是"保存成功但偏置没了"——所以断言落库后的行数与倍率。</li>
 *   <li><b>地点必须先存在</b>：往不存在的地点插偏置会成功落库但永远读不回来，
 *       因为偏置是按地点 id 关联的。</li>
 *   <li><b>删除要如实返回 404</b>：{@code affected == 0} 与"删成功"是两条分支，
 *       合并会让前端把"本来就没有"显示成"删除成功"。</li>
 * </ul>
 */
class AdminMapEventBiasResourceTest extends TestBase {

    private static final long QINGYUN_VILLAGE = 1L;
    private static final long NO_SUCH_LOCATION = 999999L;
    private static final String EVENT_KEY = "test_event";

    private final AdminMapEventBiasResource resource = new AdminMapEventBiasResource();

    @BeforeEach
    void setUp() throws SQLException {
        // 测试库跨轮次复用，map_locations 可能是空的；不灌出厂数据的话
        // existsLocation 一律 false，所有 upsert 都会以"地点不存在"被拒。
        ServiceRegistry.getMapService().ensureInitialized();
        clearBias();
    }

    @AfterEach
    void tearDown() throws SQLException {
        clearBias();
    }

    /** 清掉本用例写的偏置，避免跨轮次污染（测试库是复用的）。 */
    private void clearBias() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM map_location_event_bias WHERE location_id = ? AND event_key = ?")) {
            ps.setLong(1, QINGYUN_VILLAGE);
            ps.setString(2, EVENT_KEY);
            ps.executeUpdate();
        }
    }

    private JsonObject callUpsert(long locationId, String eventKey, double multiplier) {
        JsonObject body = new JsonObject();
        body.addProperty("locationId", locationId);
        body.addProperty("eventKey", eventKey);
        body.addProperty("multiplier", multiplier);
        String json = (String) resource.upsertMapEventBias(body.toString()).getEntity();
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private JsonObject callDelete(long locationId, String eventKey) {
        String json = (String) resource.deleteMapEventBias(locationId, eventKey).getEntity();
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private double readMultiplier() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT weight_multiplier FROM map_location_event_bias "
                             + "WHERE location_id = ? AND event_key = ?")) {
            ps.setLong(1, QINGYUN_VILLAGE);
            ps.setString(2, EVENT_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : Double.NaN;
            }
        }
    }

    private int countRows() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM map_location_event_bias "
                             + "WHERE location_id = ? AND event_key = ?")) {
            ps.setLong(1, QINGYUN_VILLAGE);
            ps.setString(2, EVENT_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    @Test
    @DisplayName("保存偏置后确实落库，且倍率是刚传的那个值")
    void upsertPersistsMultiplier() throws SQLException {
        JsonObject out = callUpsert(QINGYUN_VILLAGE, EVENT_KEY, 2.5);
        assertEquals(200, out.get("code").getAsInt(), out.toString());

        assertEquals(1, countRows(), "先删后插之后应恰有一行，重复保存不该累积");
        assertEquals(2.5, readMultiplier(), 1e-9);
    }

    @Test
    @DisplayName("重复保存是覆盖而不是插第二行（先删后插生效）")
    void upsertIsReplaceNotInsert() throws SQLException {
        callUpsert(QINGYUN_VILLAGE, EVENT_KEY, 1.5);
        callUpsert(QINGYUN_VILLAGE, EVENT_KEY, 3.0);

        assertEquals(1, countRows(), "同一条 (location_id, event_key) 必须只有一行");
        assertEquals(3.0, readMultiplier(), 1e-9, "倍率应被后一次保存覆盖");
    }

    @Test
    @DisplayName("地点不存在时拒绝，且不落库")
    void upsertRejectsUnknownLocation() throws SQLException {
        JsonObject out = callUpsert(NO_SUCH_LOCATION, EVENT_KEY, 2.0);
        assertEquals(400, out.get("code").getAsInt(), out.toString());
        assertTrue(out.get("message").getAsString().contains("地点不存在"), out.get("message").getAsString());

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM map_location_event_bias WHERE location_id = ?")) {
            ps.setLong(1, NO_SUCH_LOCATION);
            try (ResultSet rs = ps.executeQuery()) {
                assertEquals(0, rs.next() ? rs.getInt(1) : 0, "被拒绝的保存不得落库");
            }
        }
    }

    @Test
    @DisplayName("倍率越界被拒绝（0~100 且大于 0）")
    void upsertRejectsOutOfRangeMultiplier() throws SQLException {
        assertEquals(400, callUpsert(QINGYUN_VILLAGE, EVENT_KEY, 0).get("code").getAsInt());
        assertEquals(400, callUpsert(QINGYUN_VILLAGE, EVENT_KEY, -1).get("code").getAsInt());
        assertEquals(400, callUpsert(QINGYUN_VILLAGE, EVENT_KEY, 101).get("code").getAsInt());
        assertEquals(0, countRows(), "被拒绝的保存不得落库");
    }

    @Test
    @DisplayName("事件 key 为空或过长被拒绝")
    void upsertRejectsBadEventKey() throws SQLException {
        assertEquals(400, callUpsert(QINGYUN_VILLAGE, "   ", 2.0).get("code").getAsInt());
        assertEquals(400, callUpsert(QINGYUN_VILLAGE, "x".repeat(65), 2.0).get("code").getAsInt());
        assertEquals(0, countRows(), "被拒绝的保存不得落库");
    }

    @Test
    @DisplayName("删除存在的偏置返回 200 与删除行数")
    void deleteRemovesRow() throws SQLException {
        callUpsert(QINGYUN_VILLAGE, EVENT_KEY, 2.0);

        JsonObject out = callDelete(QINGYUN_VILLAGE, EVENT_KEY);
        assertEquals(200, out.get("code").getAsInt(), out.toString());
        assertEquals(1, out.getAsJsonObject("data").get("deleted").getAsInt());
        assertEquals(0, countRows(), "删除之后不该留下任何行");
    }

    @Test
    @DisplayName("删除不存在的偏置返回 404，不假装成功")
    void deleteMissingReturns404() throws SQLException {
        JsonObject out = callDelete(QINGYUN_VILLAGE, "never_saved");
        assertEquals(404, out.get("code").getAsInt(), out.toString());
    }

    @Test
    @DisplayName("删除参数非法返回 400")
    void deleteRejectsBadParams() {
        assertEquals(400, callDelete(0, EVENT_KEY).get("code").getAsInt());
        assertEquals(400, callDelete(-5, EVENT_KEY).get("code").getAsInt());
        assertEquals(400, callDelete(QINGYUN_VILLAGE, "   ").get("code").getAsInt());
        assertEquals(400, callDelete(QINGYUN_VILLAGE, null).get("code").getAsInt());
    }
}
