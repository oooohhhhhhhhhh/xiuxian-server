package com.mtxgdn.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出厂配置不得再带默认管理员口令，且首次启动必须能拿到一个随机口令。
 *
 * <p><b>缺陷形态</b>：{@code src/main/resources/application.yml} 里
 * {@code admin.password_hash} 一直是 {@code admin123} 的 bcrypt 哈希，随 jar 分发给
 * 每个新装实例。bcrypt 哈希本身不可逆，但<b>这一个哈希是公开的</b> —— 拿到任何一份部署
 * 都能用 {@code admin123} 登进管理后台，而后台可以改数据库连接串、重置任意玩家密码、
 * 下发全服公告。</p>
 *
 * <p>本测试守三件事：</p>
 * <ol>
 *   <li>模板里的 {@code admin.password_hash} 必须为空（防止有人再把默认值加回来）；</li>
 *   <li>{@link AppConfig} 的运行期覆盖确实生效且能清除（自动生成的口令靠它让本进程
 *       立刻读到新哈希）；</li>
 *   <li>自动生成的口令长度、字符集与随机性符合预期。</li>
 * </ol>
 */
class AdminCredentialBootstrapTest {

    /** 曾经随 jar 分发的默认口令哈希（admin123）。出现即视为回退。 */
    private static final String KNOWN_DEFAULT_HASH =
            "$2a$10$njJJacGfGPEX1FHD6MB1HeKEgjfWx5zF2nrGo.gEbdjb3Q.KA3gcC";

    @Test
    @DisplayName("application.yml 不得再携带出厂默认管理员口令")
    void shippedConfigHasNoDefaultAdminPassword() throws Exception {
        Path yml = Paths.get("src/main/resources/application.yml");
        assertTrue(Files.exists(yml), "找不到 application.yml: " + yml.toAbsolutePath());
        String text = Files.readString(yml, StandardCharsets.UTF_8);

        assertFalse(text.contains(KNOWN_DEFAULT_HASH),
                "application.yml 又带上了 admin123 的默认哈希 —— 这会让每个新装实例"
                        + "都有公开口令，必须留空由首次启动自动生成或由安装向导设置");

        String hashLine = text.lines()
                .filter(l -> l.trim().startsWith("password_hash:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("application.yml 里找不到 admin.password_hash"));
        String value = hashLine.substring(hashLine.indexOf(':') + 1).trim();
        assertTrue(value.startsWith("\"\"") || value.startsWith("''") || value.isEmpty(),
                "admin.password_hash 必须留空，实际: " + hashLine);
    }

    @Test
    @DisplayName("运行期覆盖优先于 application.yml，且可清除")
    void runtimeOverrideTakesPrecedence() {
        String key = "admin.password_hash";
        String original = AppConfig.get(key, "");
        try {
            AppConfig.setRuntimeOverride(key, "runtime-value");
            assertEquals("runtime-value", AppConfig.get(key),
                    "运行期覆盖必须立即对 AppConfig.get 生效，否则自动生成的口令"
                            + "在本进程内不可见，表现为「打印了密码却登不进去」");
        } finally {
            AppConfig.clearRuntimeOverrides();
        }
        assertEquals(original, AppConfig.get(key, ""), "清除覆盖后必须回到配置文件的值");
    }

    @Test
    @DisplayName("自动生成的管理员口令长度、字符集与随机性符合预期")
    void generatedPasswordIsStrongEnough() throws Exception {
        Method generator = com.mtxgdn.Main.class.getDeclaredMethod("generateAdminPassword");
        generator.setAccessible(true);

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            String pwd = (String) generator.invoke(null);
            assertEquals(20, pwd.length(), "自动生成的口令长度应为 20，实际: " + pwd);
            assertTrue(pwd.matches("[A-Za-z0-9!@#%^&*\\-_=+]+"),
                    "口令只能包含约定的字符集，实际: " + pwd);
            assertFalse(pwd.matches(".*[0O1lI].*"),
                    "口令不应包含易混淆字符 0/O/1/l/I（运维要手抄），实际: " + pwd);
            seen.add(pwd);
        }
        assertEquals(20, seen.size(), "20 次生成不应出现重复（说明随机源可用）");
    }
}
