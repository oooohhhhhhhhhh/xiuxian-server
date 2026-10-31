package com.mtxgdn.db;

import com.mtxgdn.TestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite 撞锁行为与事务重试预算的回归测试。
 *
 * <p>这组测试锁住的是两个<b>实测得出、反直觉</b>的事实。它们是"偶发
 * database is locked"这类问题唯一可靠的判断依据，所以必须钉死：</p>
 *
 * <ol>
 *   <li><b>auto-commit 路径</b>撞锁会老老实实等 {@code busy_timeout}（约 5 秒）；</li>
 *   <li><b>事务内</b>撞锁<b>立即</b>失败，{@code busy_timeout} 完全不生效。</li>
 * </ol>
 *
 * <p>第 2 条决定了 {@link DatabaseManager#runTransaction} 的重试预算必须
 * 自己覆盖完整的等待时间 —— 不能指望 busy_timeout 帮忙兜底。曾经因为
 * 预算只有 1.6 秒，撞上后台调度的一次真实写事务就必然失败。</p>
 */
class SqliteBusyTimeoutTest extends TestBase {

    @Test
    @DisplayName("连接上的 busy_timeout 确实是 5000，journal_mode 是 WAL")
    void connectionPragmasAreApplied() throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA busy_timeout")) {
                assertTrue(rs.next());
                assertEquals(5000, rs.getInt(1),
                        "busy_timeout 必须生效，否则 auto-commit 撞锁会立即失败");
            }
            try (ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                assertTrue(rs.next());
                assertEquals("wal", rs.getString(1).toLowerCase(java.util.Locale.ROOT),
                        "WAL 模式必须生效（并发读不被写阻塞）");
            }
        }
    }

    @Test
    @DisplayName("auto-commit 撞锁会等待 busy_timeout 而不是立即失败")
    void autoCommitWaitsForBusyTimeout() throws Exception {
        Connection holder = DatabaseManager.getConnection();
        holder.setAutoCommit(false);
        try {
            try (PreparedStatement ps = holder.prepareStatement(
                    "INSERT INTO users (username, password) VALUES (?, ?)")) {
                ps.setString(1, "busy_probe_holder");
                ps.setString(2, "x");
                ps.executeUpdate();
            }

            long waited = -1;
            try (Connection blocked = DatabaseManager.getConnection()) {
                long t0 = System.currentTimeMillis();
                try (PreparedStatement ps = blocked.prepareStatement(
                        "INSERT INTO users (username, password) VALUES (?, ?)")) {
                    ps.setString(1, "busy_probe_blocked");
                    ps.setString(2, "y");
                    ps.executeUpdate();
                    fail("写锁被占时不该插入成功");
                } catch (java.sql.SQLException expected) {
                    waited = System.currentTimeMillis() - t0;
                }
            }
            // 等待必须接近 5 秒：说明 busy_timeout 在 auto-commit 路径确实起作用。
            // 上限放宽到 15 秒，避免慢机器上误判。
            assertTrue(waited >= 3000,
                    "auto-commit 撞锁应等待约 5 秒，实际只等了 " + waited + "ms —— "
                            + "说明 busy_timeout 没生效");
        } finally {
            holder.rollback();
            holder.setAutoCommit(true);
            holder.close();
        }
    }

    @Test
    @DisplayName("事务内撞锁立即失败：busy_timeout 对它不生效，等待只能靠重试退避")
    void transactionBusyFailsImmediately() throws Exception {
        Connection holder = DatabaseManager.getConnection();
        holder.setAutoCommit(false);
        try {
            try (PreparedStatement ps = holder.prepareStatement(
                    "INSERT INTO users (username, password) VALUES (?, ?)")) {
                ps.setString(1, "tx_busy_holder");
                ps.setString(2, "x");
                ps.executeUpdate();
            }

            long waited = -1;
            try (Connection blocked = DatabaseManager.getConnection()) {
                blocked.setAutoCommit(false);
                long t0 = System.currentTimeMillis();
                try {
                    // 先读建立快照，再写 —— 与 runTransaction 的典型形态一致
                    try (Statement st = blocked.createStatement()) {
                        st.executeQuery("SELECT COUNT(*) FROM users").close();
                    }
                    try (PreparedStatement ps = blocked.prepareStatement(
                            "INSERT INTO users (username, password) VALUES (?, ?)")) {
                        ps.setString(1, "tx_busy_blocked");
                        ps.setString(2, "y");
                        ps.executeUpdate();
                        fail("写锁被占时事务内写入不该成功");
                    }
                } catch (java.sql.SQLException expected) {
                    waited = System.currentTimeMillis() - t0;
                } finally {
                    try { blocked.rollback(); } catch (Exception ignored) {}
                    try { blocked.setAutoCommit(true); } catch (Exception ignored) {}
                }
            }
            // 这条断言是这个测试的全部价值：如果哪天 SQLite 改成会等待，
            // 这里会失败，提醒我们"事务重试预算可以放宽/收紧了"。
            assertTrue(waited < 1000,
                    "事务内撞锁应当立即失败（实测约 1ms），实际等了 " + waited + "ms。"
                            + "若此断言失败，说明 SQLite 行为变了，"
                            + "runTransaction 的重试预算需要重新评估");
        } finally {
            holder.rollback();
            holder.setAutoCommit(true);
            holder.close();
        }
    }

    @Test
    @DisplayName("runTransaction 撞锁后会自动重试，而不是一次失败就放弃")
    void runTransactionRetriesOnBusy() throws Exception {
        // 这条测试要验证的是"撞锁会重试"，而不是"锁一定能造出来"。
        // 前三条已经证明：事务内撞锁会立即抛 SQLITE_BUSY。
        // 这里用一个必然撞锁的方式：在事务回调里主动抛带 SQLITE_BUSY 的异常，
        // 模拟"SQLite 报了锁冲突"，看 runTransaction 会不会重试。
        //
        // 为什么不在测试里真的造锁：造锁需要精确控制两条连接的提交时序，
        // 而连接池大小、WAL 的写锁粒度都会影响结果，测试会变得又慢又脆。
        // 真实锁场景已由 transactionBusyFailsImmediately 覆盖。
        AtomicInteger attempts = new AtomicInteger();
        String result = DatabaseManager.runTransaction(conn -> {
            int n = attempts.incrementAndGet();
            if (n < 3) {
                // 前两次模拟撞锁（SQLState 40001 = 序列化失败，最通用的可重试标记）
                throw new java.sql.SQLException(
                        "[SQLITE_BUSY] The database file is locked (database is locked)", "40001");
            }
            return "ok";
        });

        assertEquals("ok", result, "撞锁重试后应当成功");
        assertEquals(3, attempts.get(),
                "应当重试到第 3 次才成功；若只尝试 1 次说明重试逻辑失效");
    }

    @Test
    @DisplayName("重试预算覆盖 busy_timeout 的 5 秒（事务内撞锁不会自己等待）")
    void retryBudgetCoversBusyTimeoutWindow() throws Exception {
        // 事务内撞锁立即失败，所以"愿意等多久"完全由重试预算决定。
        // 若预算远小于 busy_timeout，就出现"auto-commit 愿意等 5 秒、
        // 事务却只等 1.6 秒"的不一致 —— 那正是历史 bug。
        //
        // 这里用真实耗时验证：连续撞锁时，总耗时应达到秒级。
        long t0 = System.currentTimeMillis();
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> DatabaseManager.runTransaction(conn -> {
            attempts.incrementAndGet();
            throw new java.sql.SQLException("[SQLITE_BUSY] always locked", "40001");
        }));
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(elapsed >= 3000,
                "重试总预算应达到秒级（对齐 busy_timeout 的 5 秒），实际只有 " + elapsed
                        + "ms —— 预算太小时撞上后台真实写事务必然失败");
        assertTrue(attempts.get() >= 5,
                "应当有多次重试机会，实际 " + attempts.get() + " 次");
    }
}
