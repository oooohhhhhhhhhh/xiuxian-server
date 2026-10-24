package com.mtxgdn.db;

import com.mtxgdn.TestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接池的生命周期不能漏连接。
 *
 * <p><b>缺陷形态</b>：{@code DatabaseManager.init()} 里有一句光秃秃的
 * {@code getConnection();} —— 预热连接池，但**从不关闭**。这条连接被永久借出：</p>
 *
 * <ul>
 *   <li>SQLite 下池子只有 4 个连接，少一个是少 25%，而且永远不会还回来；</li>
 *   <li>更糟的是 {@code init()} 可以重复调用（测试里每个测试类都会调一次），
 *       每次都会新建一个 Hikari 池却不关旧池 —— 跑完整个测试套件，
 *       进程里会堆着几十个池、上百条已泄漏的连接；</li>
 *   <li>线上表现是高峰期莫名其妙的
 *       {@code Connection is not available, request timed out after 10000ms}，
 *       堆栈指向某条毫不相干的业务查询（本仓库里是
 *       {@code ItemService.getInventoryUsedSlots}），查起来完全不像连接池的锅。</li>
 * </ul>
 *
 * <p>本测试直接把"池里有没有漏出来的借出连接"当成断言对象，
 * 这样下次谁再写一句借了不还的 {@code getConnection()} 就会当场失败。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabasePoolLifecycleTest extends TestBase {

    @Test
    @Order(1)
    @DisplayName("init() 之后不许有借出未还的连接")
    void initDoesNotLeakAWarmupConnection() {
        assertEquals(0, DatabaseManager.getActiveConnectionCount(),
                "init() 结束后仍有借出未归还的连接，说明预热连接漏了 —— "
                        + "池里会被永久占掉一个名额（SQLite 只有 4 个）");
    }

    @Test
    @Order(2)
    @DisplayName("重复 init() 必须先把旧池关掉，而不是再建一个")
    void reInitClosesThePreviousPool() throws Exception {
        // 先借一条连接出来，把池标记成"有借出"
        Connection held;
        try (Connection ignored = DatabaseManager.getConnection()) {
            // 只是为了确认池是活的
        }
        held = DatabaseManager.getConnection();
        try {
            assertEquals(1, DatabaseManager.getActiveConnectionCount(), "借出一条后应计为 1");

            // 重复初始化：旧池必须被关掉（关池会把借出的连接一并作废）
            DatabaseManager.init(TestBase.testDbUrl("xiuxian_test.db"), "", "");

            assertEquals(0, DatabaseManager.getActiveConnectionCount(),
                    "重复 init() 之后旧池应已关闭、旧连接已作废，"
                            + "否则每 init 一次就漏一个池（测试里每个测试类都会 init 一次）");
            assertTrue(DatabaseManager.getMaxPoolSize() >= 2, "新池应已建好");
        } finally {
            try {
                held.close();
            } catch (Exception ignored) {
                // 旧池已关闭时这条连接本来就失效了，关不上是预期内的
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("借满池子再全部归还后，借出数必须回到 0")
    void borrowingAndReturningLeavesNoResidue() throws Exception {
        int max = DatabaseManager.getMaxPoolSize();
        assertTrue(max >= 2, "池上限应至少为 2，实际 " + max);

        // 只借池上限 - 1 条，保证不会把自己卡死在等连接上
        int take = max - 1;
        List<Connection> conns = new ArrayList<>();
        try {
            for (int i = 0; i < take; i++) {
                conns.add(DatabaseManager.getConnection());
            }
            assertEquals(take, DatabaseManager.getActiveConnectionCount(),
                    "借出 " + take + " 条后借出数应为 " + take);
        } finally {
            for (Connection c : conns) {
                c.close();
            }
        }
        assertEquals(0, DatabaseManager.getActiveConnectionCount(),
                "全部归还后借出数必须回到 0");
    }
}
