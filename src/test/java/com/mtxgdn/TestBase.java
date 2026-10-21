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
