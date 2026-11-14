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
        // 顺序很重要：先让容器停掉旧的后台任务，再删库、再重建连接池。
        //
        // 为什么先停：JUnit 对**每个**测试类都会调用一次父类的 @BeforeAll，
        // 本套件有几十个类继承本类。后台定时任务（自动回血、增益过期、
        // 拍卖/坊市/宗门清扫）如果横跨"关旧池 → 建新池"，就会在旧连接被遗弃、
        // 新库还在建表的时候进来写 —— 表现为跨测试类随机的 SQLITE_BUSY，
        // 报错的往往是一条毫不相干的 INSERT。
        //
        // 现在的实现：调度器归 ServiceContainer 所有（BackgroundTaskOwner），
        // reset() 会 close() 旧容器、按逆序停掉它启动过的全部任务。
        // 因此这里不再需要逐个服务点 shutdown —— 那种写法漏一个就前功尽弃，
        // 而且新增服务时没人会记得回来补。测试进程从不调用
        // startBackgroundTasks()，所以换进来的新容器本来就是"零后台线程"的。
        com.mtxgdn.common.service.ServiceRegistry.reset();

        File dbFile = testDbFile("xiuxian_test.db");
        if (dbFile.exists()) {
            dbFile.delete();
        }
        // 语言包必须在任何断言中文案之前加载：测试进程不走 Main，
        // 不显式 load 时 I18n.t 只会返回 key 本身。
        I18nManager.load();
        DatabaseManager.init(testDbUrl("xiuxian_test.db"), "", "");
        JwtUtil.init(true); // test env: install mode generates a temporary key

        // 图鉴奖励会改金币/灵石/背包，而很多单测在断言"操作后余额正好是多少"。
        // 这里统一关掉图鉴 hook（旁路系统不该影响任何既有测试的精确断言）；
        // 图鉴自己的集成测试会在子类 @BeforeAll 里显式打开。
        // JUnit 保证父类 @BeforeAll 先于子类执行，所以子类的打开不会被覆盖。
        com.mtxgdn.game.collection.CollectionHooks.setEnabledForTest(false);
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
