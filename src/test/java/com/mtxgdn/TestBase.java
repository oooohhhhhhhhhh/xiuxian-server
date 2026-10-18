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
        File dbFile = new File("xiuxian_test.db");
        if (dbFile.exists()) {
            dbFile.delete();
        }
        // 语言包必须在任何断言中文案之前加载：测试进程不走 Main，
        // 不显式 load 时 I18n.t 只会返回 key 本身。
        I18nManager.load();
        DatabaseManager.init("jdbc:sqlite:xiuxian_test.db", "", "");
        JwtUtil.init(true); // test env: install mode generates a temporary key
    }
}
