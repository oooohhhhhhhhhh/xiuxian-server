package com.mtxgdn.rest;

import com.google.gson.JsonArray;
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
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 管理台地图接口的行为测试。
 *
 * <p>端点存在性与权限码由 {@code EndpointAlignmentTest} 用反射锁定，这里验证真正的行为：
 * 更新之后地图缓存是否<b>真的</b>热重载了 —— 若不重载，管理员改完看不到变化，
 * 又会回到"以为改错了"的老问题上。</p>
 */
class AdminMapResourceTest extends TestBase {

    private static final long QINGYUN_VILLAGE = 1L;
    private static final long WENDAO_PEAK = 10L;

    private final AdminResource resource = new AdminResource();

    @BeforeEach
    void setUp() throws SQLException {
        resetQingyunVillage();
    }

    @AfterEach
    void tearDown() throws SQLException {
        resetQingyunVillage();
    }

    /** 把青云村恢复成出厂值，避免用例之间互相污染（测试库跨轮次复用）。 */
    private void resetQingyunVillage() throws SQLException {
        // 先确保出厂数据已入库，否则 UPDATE 会打在空表上（0 行）而静默无效
        ServiceRegistry.getMapService().ensureInitialized();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE map_locations SET name = ?, cultivation_bonus = ?, exploration_bonus = ?, "
                             + "is_safe_zone = ? WHERE id = ?")) {
            ps.setString(1, "青云村");
            ps.setDouble(2, 1.00);
            ps.setDouble(3, 1.00);
            ps.setBoolean(4, true);
            ps.setLong(5, QINGYUN_VILLAGE);
            ps.executeUpdate();
        }
        ServiceRegistry.getMapService().reload();
    }

    private JsonObject callGet() {
        String json = (String) resource.getMapLocations().getEntity();
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private JsonObject callUpdate(long id, JsonObject body) {
        String json = (String) resource.updateMapLocation(id, body.toString()).getEntity();
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject findLocation(JsonObject data, long id) {
        JsonArray arr = data.getAsJsonArray("locations");
        for (int i = 0; i < arr.size(); i++) {
            JsonObject o = arr.get(i).getAsJsonObject();
            if (o.get("id").getAsLong() == id) return o;
        }
        return null;
    }

    @Test
    @DisplayName("地图总览返回全部地点、连接数与区域分组")
    void testListLocations() {
        JsonObject out = callGet();
        assertEquals(200, out.get("code").getAsInt(), out.toString());

        JsonObject data = out.getAsJsonObject("data");
        assertEquals(15, data.get("locationCount").getAsInt());
        assertEquals(17, data.get("connectionCount").getAsInt(), "出厂应有 17 条无向边");
        assertTrue(data.getAsJsonArray("regions").size() > 1, "应有多于一个区域");

        // 问道峰：境界名必须解析出来（后台看数字看不出门槛高低），倍率来自数据库
        JsonObject wendao = findLocation(data, WENDAO_PEAK);
        assertNotNull(wendao, "总览应包含问道峰");
        assertEquals("大乘期", wendao.get("minRealmName").getAsString());
        assertEquals(7, wendao.get("minRealm").getAsInt());
        assertEquals(1.50, wendao.get("cultivationBonus").getAsDouble(), 1e-9);
        assertFalse(wendao.get("safeZone").getAsBoolean());

        // 青云村是安全区，且带邻居与路程
        JsonObject village = findLocation(data, QINGYUN_VILLAGE);
        assertTrue(village.get("safeZone").getAsBoolean());
        assertEquals(1, village.getAsJsonArray("neighbors").size(), "青云村只有青云山一个邻居");
        assertTrue(village.getAsJsonArray("neighbors").get(0).getAsJsonObject()
                .get("travelSeconds").getAsInt() > 0);
    }

    @Test
    @DisplayName("更新地点参数后地图缓存立即热重载")
    void testUpdateTriggersReload() {
        assertEquals(1.00, ServiceRegistry.getMapService().getCultivationBonus(QINGYUN_VILLAGE), 1e-9);

        JsonObject body = new JsonObject();
        body.addProperty("cultivationBonus", 1.75);
        body.addProperty("explorationBonus", 1.40);
        JsonObject out = callUpdate(QINGYUN_VILLAGE, body);
        assertEquals(200, out.get("code").getAsInt(), out.toString());

        // 关键断言：不重启进程也能读到新值（说明 reload 被触发了）
        assertEquals(1.75, ServiceRegistry.getMapService().getCultivationBonus(QINGYUN_VILLAGE), 1e-9,
                "更新后地图缓存未热重载");
        assertEquals(1.40, ServiceRegistry.getMapService().getExplorationBonus(QINGYUN_VILLAGE), 1e-9);
    }

    @Test
    @DisplayName("未传的字段不会被改动（没传 ≠ 清空）")
    void testPartialUpdateKeepsOtherFields() {
        JsonObject body = new JsonObject();
        body.addProperty("explorationBonus", 1.30);
        assertEquals(200, callUpdate(QINGYUN_VILLAGE, body).get("code").getAsInt());

        JsonObject village = findLocation(callGet().getAsJsonObject("data"), QINGYUN_VILLAGE);
        assertEquals("青云村", village.get("name").getAsString(), "名称不应被清空");
        assertTrue(village.get("safeZone").getAsBoolean(), "安全区标记不应被清空");
        assertEquals(1.00, village.get("cultivationBonus").getAsDouble(), 1e-9, "灵气倍率不应被清空");
        assertEquals(1.30, village.get("explorationBonus").getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("非法倍率被拒绝且不落库")
    void testRejectsInvalidBonus() {
        JsonObject body = new JsonObject();
        body.addProperty("cultivationBonus", 0);   // 低于下限 0.1
        JsonObject out = callUpdate(QINGYUN_VILLAGE, body);

        assertEquals(400, out.get("code").getAsInt(), out.toString());
        assertTrue(out.get("message").getAsString().contains("0.1~100"), out.get("message").getAsString());
        assertEquals(1.00, ServiceRegistry.getMapService().getCultivationBonus(QINGYUN_VILLAGE), 1e-9,
                "被拒绝的更新不得落库");
    }

    @Test
    @DisplayName("未知地点返回 404")
    void testUnknownLocationReturns404() {
        JsonObject body = new JsonObject();
        body.addProperty("cultivationBonus", 1.5);
        JsonObject out = callUpdate(99999L, body);
        assertEquals(404, out.get("code").getAsInt(), out.toString());
    }

    @Test
    @DisplayName("空请求体被拒绝（没有任何可更新字段）")
    void testEmptyUpdateRejected() {
        JsonObject out = callUpdate(QINGYUN_VILLAGE, new JsonObject());
        assertEquals(400, out.get("code").getAsInt(), out.toString());
    }
}
