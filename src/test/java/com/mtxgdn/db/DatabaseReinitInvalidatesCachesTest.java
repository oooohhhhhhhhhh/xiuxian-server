package com.mtxgdn.db;

import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.service.MapService;
import com.mtxgdn.permission.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 换库之后，内存缓存必须跟着失效。
 *
 * <p><b>缺陷形态</b>：{@code DatabaseManager.init()} 是<b>可重复调用</b>的
 * （测试里每个测试类调一次；运行时切换配置也会走到它）。它会把连接池换成新库，
 * 但那些"首次访问时从库里读一遍、之后一直用内存副本"的单例并不知道库已经换了 ——
 * 它们仍然拿着旧库的内容，而旧库可能已经被删掉了。</p>
 *
 * <p><b>真实后果</b>（不是理论推演，是实测踩到的）：全量跑测试时
 * {@code AdminMapEventBiasResourceTest} 稳定失败三次 ——
 * 前一个测试类 {@code MultiPlayerFullTest} 把池子 init 到了另一个库文件，
 * {@code MapService} 的 {@code initialized} 仍是 {@code true}，于是
 * {@code ensureInitialized()} 直接返回，新库里的 {@code map_locations}
 * <b>从没被播种过</b>。接口于是对所有地点回 "地点不存在"，
 * 而库里明明有表、也能正常查。</p>
 *
 * <p>排查时最迷惑的一点是：单独跑那个测试类<b>永远通过</b>，
 * 只有跟别的换库测试类排在同一个 JVM 里才失败 —— 这类 bug 很容易被当成
 * "测试不稳定"而忽略过去，实际是生产环境换库后缓存指向旧库的同一个问题。</p>
 */
class DatabaseReinitInvalidatesCachesTest extends TestBase {

    private static final String ALT_DB = "test_reinit_alt.db";

    @AfterEach
    void restoreMainDatabase() {
        // 必须切回来：同一 JVM 里后面的测试类都假设池子指向 xiuxian_test.db
        DatabaseManager.init(TestBase.testDbUrl("xiuxian_test.db"), "", "");
    }

    @Test
    @DisplayName("换库后地图缓存必须失效：新库的地图数据要重新播种，而不是沿用旧库")
    void mapCacheIsInvalidatedOnReinit() throws SQLException {
        MapService mapService = ServiceRegistry.getMapService();
        mapService.ensureInitialized();
        assertTrue(mapService.getLocation(1L) != null,
                "前置条件：主库应已有出厂地点（id=1）");

        // 切到一个全新的空库：它里面什么都还没有，initTable 会建表但不会播种地图数据
        File altFile = TestBase.testDbFile(ALT_DB);
        if (altFile.exists() && !altFile.delete()) {
            fail("无法删除旧的重 Init 测试库: " + altFile.getAbsolutePath());
        }
        DatabaseManager.init(TestBase.testDbUrl(ALT_DB), "", "");

        // 换库之后缓存必须已经失效 —— 若仍是旧值，说明监听没生效
        mapService.ensureInitialized();

        assertTrue(locationCount() > 0,
                "换库后新库的 map_locations 必须被重新播种；为 0 说明 MapService "
                        + "仍认为「已初始化」，跳过了对新库的加载");
        assertNotNull(mapService.getLocation(1L),
                "换库后应当能读到新库里的出厂地点；读不到说明缓存还指向旧库");
    }

    @Test
    @DisplayName("换库后权限缓存必须失效，不能拿旧库的权限组做判断")
    void permissionCacheIsInvalidatedOnReinit() throws SQLException {
        PermissionService.refreshGroupCache();

        DatabaseManager.init(TestBase.testDbUrl(ALT_DB), "", "");
        // 换库后不该再命中旧库的缓存：这里只验证"没抛异常且能重新加载"，
        // 因为新库的权限组内容取决于 initDefaultData 是否被调用过。
        assertDoesNotThrow(PermissionService::refreshGroupCache,
                "换库后重新加载权限组不得失败（旧实现会拿着已关闭连接池的缓存）");
    }

    /** 直接查库数地点行数，绕开缓存。 */
    private int locationCount() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM map_locations")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
