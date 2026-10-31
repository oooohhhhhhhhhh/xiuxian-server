package com.mtxgdn;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.i18n.I18nManager;
import com.mtxgdn.util.JwtUtil;
import org.junit.jupiter.api.BeforeAll;

import java.io.File;

/**
 * Unit test base: initializes the shared SQLite test database and JWT.
 * The whole suite runs in one JVM (surefire forkCount=0), so the database
 * pointer is initialized once and all test classes reuse the same test DB file.
 */
public class TestBase {

    @BeforeAll
    static void setUpDatabase() {
        // 顺序很重要：先停掉所有后台调度器，再删库、再重建连接池。
        //
        // 为什么要停：JUnit 对**每个**测试类都会调用一次父类的 @BeforeAll，
        // 而本套件有 45 个类继承本类 —— 这段代码会被执行 45 次。
        // 与此同时 PlayerService / BuffService 等 9 个服务的**静态块**里启动的调度器
        // （自动回血每 10 秒 UPDATE players、玩家快照回写、拍卖/坊市/宗门清扫）
        // 在整个测试进程中**从不停止**。
        //
        // 不停会怎样：它们横跨每一次"关旧池 → 建新池"。旧池 close() 时若某条连接
        // 正被调度线程持有，该连接被直接遗弃，其上未提交的写事务让 SQLite 的写锁
        // 不释放；紧接着新池刚建好、库还是空的时候，调度线程又进来写 ——
        // 撞上正在建表的连接。表现就是**跨测试类随机**出现的
        // [SQLITE_BUSY] The database file is locked，报错的往往是一条毫不相干的
        // INSERT，肇事者早已跑完，堆栈也早没了。
        stopBackgroundSchedulers();

        File dbFile = testDbFile("xiuxian_test.db");
        if (dbFile.exists()) {
            dbFile.delete();
        }
        // 语言包必须在任何断言中文案之前加载：测试进程不走 Main，
        // 不显式 load 时 I18n.t 只会返回 key 本身。
        I18nManager.load();
        DatabaseManager.init(testDbUrl("xiuxian_test.db"), "", "");
        JwtUtil.init(true); // test env: install mode generates a temporary key
    }

    /**
     * 停掉所有会在后台写库的调度器。
     *
     * <p>每个都单独 try/catch：某个服务尚未被加载（类还没初始化）时调用它的
     * static 方法会触发类初始化，反而把调度器启起来 —— 那种情况下抛出的异常
     * 直接忽略即可，因为"没启动过"和"已停掉"对后续断言是等价的。</p>
     *
     * <p>注意这里刻意<b>不</b>调用 {@code DatabaseManager.shutdown()}：
     * 紧接着就要 {@code init()} 重建池，先关再建没有意义。</p>
     */
    private static void stopBackgroundSchedulers() {
        try { com.mtxgdn.game.service.PlayerService.shutdown(); } catch (Throwable ignored) {}
        try { com.mtxgdn.game.service.BuffService.shutdownScheduler(); } catch (Throwable ignored) {}
        try { com.mtxgdn.game.service.CaveService.shutdown(); } catch (Throwable ignored) {}
        try { com.mtxgdn.game.service.EconomyService.shutdown(); } catch (Throwable ignored) {}
        try { com.mtxgdn.game.service.FormationService.shutdown(); } catch (Throwable ignored) {}
        try { com.mtxgdn.game.service.SeasonService.shutdown(); } catch (Throwable ignored) {}
        try { com.mtxgdn.game.service.SectService.shutdownSuccessionSweeper(); } catch (Throwable ignored) {}
        try { com.mtxgdn.game.service.TradeService.shutdown(); } catch (Throwable ignored) {}
        try { com.mtxgdn.playerdata.PlayerDataService.shutdown(); } catch (Throwable ignored) {}
        try { com.mtxgdn.game.service.FarmService.shutdownScheduler(); } catch (Throwable ignored) {}
    }

    /**
     * 测试库文件统一落在 {@code target/test-db/} 下。
     *
     * <p>以前测试库直接建在仓库根目录（{@code xiuxian_test.db}、{@code test_full.db} …），
     * 每跑一次就留一堆 {@code .db-wal} / {@code .db-shm} 在工作区里；
     * 虽然 {@code .gitignore} 挡住了误提交，但它们会一直堆到有人手工清理为止，
     * 而 {@code mvn clean} 本来就应该把测试产物一起带走。</p>
     */
    public static File testDbFile(String name) {
        File dir = new File("target", "test-db");
        dir.mkdirs();
        return new File(dir, name);
    }

    /** 与 {@link #testDbFile(String)} 对应的 JDBC URL（用正斜杠，Windows 下同样可用）。 */
    public static String testDbUrl(String name) {
        return "jdbc:sqlite:" + testDbFile(name).getPath().replace('\\', '/');
    }
}
