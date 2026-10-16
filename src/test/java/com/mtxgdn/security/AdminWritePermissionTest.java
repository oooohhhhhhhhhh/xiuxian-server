package com.mtxgdn.security;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.common.command.CommandScanner;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.permission.PermissionCode;
import com.mtxgdn.permission.RequirePermission;
import com.mtxgdn.rest.AdminResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code admin.status} 只读性回归测试。
 * <p>
 * 背景：{@code admin.status} 的显示名是「查看服务器状态」，但 {@code PermissionService}
 * 给 {@code MODERATOR} 组的默认权限里恰好包含它。此前多处以它门控**写操作**：
 * 修改灵根、编辑玩家属性、增删玩家能量、发送全服公告 —— 等于版主可以随意改玩家数据。
 * <p>
 * 本测试锁定两条不变量，防止同类问题再次混入：
 * <ol>
 *   <li>任何权限码为 {@code admin.status} 的端点都必须是只读 GET；</li>
 *   <li>任何写端点（POST/PUT/DELETE/PATCH）都必须显式声明权限码，且不得是 {@code admin.status}。</li>
 * </ol>
 * 另外钉住本次改动涉及的具体权限归属（读用 {@code admin.status}、写用
 * {@code admin.users.manage} / {@code admin.announce}）。
 * <p>
 * 命令通过 {@code Command} 构造函数自注册，发现依赖 {@link CommandScanner} 扫描包
 * （与 {@code Main.initializeCommands} 一致），因此测试需先扫描。
 * 本测试不触碰数据库，故不继承 {@code TestBase}。
 */
class AdminWritePermissionTest {

    /** 只读权限码：只允许挂在 GET 上。 */
    private static final String READ_ONLY_PERM = "admin.status";

    /** 玩家数据写操作使用的权限码（与 REST 侧 /players/{id}/edit 等保持一致）。 */
    private static final String PLAYER_WRITE_PERM = "admin.users.manage";

    /** 登录端点自身不需要权限（未登录时也必须能调）。 */
    private static final String LOGIN_PATH = "/login";

    @BeforeAll
    static void scanCommands() {
        CommandScanner.scanAndRegister("com.mtxgdn.onebot.command");
        assertTrue(CommandRegistry.count() > 0, "命令扫描失败：未注册任何命令");
    }

    /** 按类型取已注册的指令实例（按类而非按名匹配，避免改名导致测试失效）。 */
    private static Command commandOf(Class<? extends Command> type) {
        for (Command cmd : CommandRegistry.getAllUnique()) {
            if (cmd.getClass() == type) {
                return cmd;
            }
        }
        throw new AssertionError("指令未注册: " + type.getName());
    }

    /** 管理台资源里带 @Path 的方法（含 HTTP 方法注解与权限注解）。 */
    private static List<Method> adminEndpoints() {
        List<Method> result = new ArrayList<>();
        for (Method m : AdminResource.class.getDeclaredMethods()) {
            if (m.getAnnotation(jakarta.ws.rs.Path.class) != null) {
                result.add(m);
            }
        }
        return result;
    }

    private static String httpVerbOf(Method m) {
        if (m.getAnnotation(jakarta.ws.rs.GET.class) != null) return "GET";
        if (m.getAnnotation(jakarta.ws.rs.POST.class) != null) return "POST";
        if (m.getAnnotation(jakarta.ws.rs.PUT.class) != null) return "PUT";
        if (m.getAnnotation(jakarta.ws.rs.DELETE.class) != null) return "DELETE";
        if (m.getAnnotation(jakarta.ws.rs.PATCH.class) != null) return "PATCH";
        return null;
    }

    private static boolean isWriteVerb(String verb) {
        return "POST".equals(verb) || "PUT".equals(verb)
                || "DELETE".equals(verb) || "PATCH".equals(verb);
    }

    // ==================== 不变量 1：admin.status 只能挂在 GET 上 ====================

    @Test
    @DisplayName("不变量: 权限码 admin.status 的指令路由必须都是只读 GET")
    void testAdminStatusOnlyGuardsGetRoutes() {
        List<String> offenders = new ArrayList<>();
        for (Command cmd : CommandRegistry.getAllUnique()) {
            List<RouteDefinition> defs = cmd.getRestEndpoints();
            if (defs == null) continue;
            for (RouteDefinition def : defs) {
                if (!READ_ONLY_PERM.equals(def.getPermission())) continue;
                String method = def.getMethod() == null ? "" : def.getMethod().toUpperCase();
                if (isWriteVerb(method)) {
                    offenders.add(cmd.getClass().getSimpleName() + " " + method + " " + def.getPath());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下写路由用只读权限 admin.status 门控（MODERATOR 组默认持有它，会获得写权限）: " + offenders);
    }

    @Test
    @DisplayName("不变量: 权限码 admin.status 的管理台接口必须都是只读 GET")
    void testAdminStatusOnlyGuardsGetEndpoints() {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (Method m : adminEndpoints()) {
            RequirePermission rp = m.getAnnotation(RequirePermission.class);
            if (rp == null || !READ_ONLY_PERM.equals(rp.value())) continue;
            checked++;
            String verb = httpVerbOf(m);
            if (isWriteVerb(verb)) {
                offenders.add(m.getName() + " " + verb + " " + m.getAnnotation(jakarta.ws.rs.Path.class).value());
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下管理台写接口用只读权限 admin.status 门控: " + offenders);
        assertTrue(checked > 0,
                "未找到任何以 admin.status 门控的管理台接口 —— 若确实全部移除，请一并更新本测试");
    }

    // ==================== 不变量 2：写端点必须有非只读权限 ====================

    @Test
    @DisplayName("不变量: 管理台写接口必须显式声明权限码，且不得复用只读权限")
    void testMutatingAdminEndpointsDeclareWritePermission() {
        List<String> missing = new ArrayList<>();
        List<String> reusedReadPerm = new ArrayList<>();
        for (Method m : adminEndpoints()) {
            String verb = httpVerbOf(m);
            if (!isWriteVerb(verb)) continue;
            String path = m.getAnnotation(jakarta.ws.rs.Path.class).value();
            // 登录端点例外：调用它时用户尚未登录，不可能有权限。
            if (LOGIN_PATH.equals(path)) continue;

            RequirePermission rp = m.getAnnotation(RequirePermission.class);
            if (rp == null) {
                missing.add(verb + " " + path);
            } else if (READ_ONLY_PERM.equals(rp.value())) {
                reusedReadPerm.add(verb + " " + path);
            }
        }
        assertTrue(missing.isEmpty(), "以下管理台写接口没有声明 @RequirePermission: " + missing);
        assertTrue(reusedReadPerm.isEmpty(),
                "以下管理台写接口复用了只读权限 admin.status: " + reusedReadPerm);
    }

    // ==================== 具体权限归属 ====================

    @Test
    @DisplayName("改玩家数据的指令必须用 admin.users.manage（与 REST /players/{id}/edit 一致）")
    void testPlayerDataMutatingCommandsUseAdminUsersManage() {
        assertPermissionOf(com.mtxgdn.onebot.command.admin.SetRootCommand.class,
                PLAYER_WRITE_PERM, "修改灵根会改写玩家属性并重算基础属性");
        assertPermissionOf(com.mtxgdn.onebot.command.admin.EditPlayerCommand.class,
                PLAYER_WRITE_PERM, "编辑玩家可直接改写等级/金币/灵力/境界等");
        assertPermissionOf(com.mtxgdn.onebot.command.admin.UsersRolesCommand.class,
                PLAYER_WRITE_PERM, "用户列表会输出全部账号、角色与 QQ 绑定（REST /users 同为该权限）");
    }

    @Test
    @DisplayName("只读的管理指令仍用 admin.status（勿一并收紧，否则版主连查看都没了）")
    void testReadOnlyAdminCommandsKeepAdminStatus() {
        assertPermissionOf(com.mtxgdn.onebot.command.admin.StatusCommand.class,
                READ_ONLY_PERM, "服务器状态是纯读取");
        assertPermissionOf(com.mtxgdn.onebot.command.admin.PlayersListCommand.class,
                READ_ONLY_PERM, "玩家列表是纯读取");
        assertPermissionOf(com.mtxgdn.onebot.command.admin.PlayerDetailCommand.class,
                READ_ONLY_PERM, "玩家详情是纯读取");
        assertPermissionOf(com.mtxgdn.onebot.command.admin.ItemsQueryCommand.class,
                READ_ONLY_PERM, "物品清单是纯读取");
    }

    @Test
    @DisplayName("能量管理必须读写分离：查看 admin.status，增删改 admin.users.manage")
    void testEnergyAdminSplitsReadAndWrite() {
        Command energy = commandOf(com.mtxgdn.onebot.command.admin.EnergyAdminCommand.class);
        // 指令级权限是「查看」的门槛，因此保持 admin.status；
        // 写操作在指令内部按 action 二次校验（见 EnergyAdminCommand.isWriteAction）。
        assertEquals(READ_ONLY_PERM, energy.getPermission(),
                "能量管理指令级权限应为只读的 admin.status（否则版主无法查看能量）");

        List<RouteDefinition> defs = energy.getRestEndpoints();
        assertNotNull(defs, "能量管理应注册 REST 路由");
        for (RouteDefinition def : defs) {
            String method = def.getMethod() == null ? "" : def.getMethod().toUpperCase();
            if (isWriteVerb(method)) {
                assertEquals(PLAYER_WRITE_PERM, def.getPermission(),
                        method + " " + def.getPath() + " 是写操作，必须用 " + PLAYER_WRITE_PERM);
            } else {
                assertEquals(READ_ONLY_PERM, def.getPermission(),
                        method + " " + def.getPath() + " 是读操作，应保持 " + READ_ONLY_PERM);
            }
        }
        // 三个写路由确实存在（防止被误删后本测试变成空转）
        List<String> writePaths = new ArrayList<>();
        for (RouteDefinition def : defs) {
            if (isWriteVerb(def.getMethod() == null ? "" : def.getMethod().toUpperCase())) {
                writePaths.add(def.getPath());
            }
        }
        assertTrue(writePaths.contains("admin/energy/set")
                        && writePaths.contains("admin/energy/add")
                        && writePaths.contains("admin/energy/remove"),
                "能量管理的三个写路由应齐全，实际: " + writePaths);
    }

    @Test
    @DisplayName("全服公告必须用专用权限 admin.announce，不得复用 admin.status")
    void testAnnounceUsesDedicatedPermission() throws Exception {
        assertNotNull(PermissionCode.fromCode("admin.announce"),
                "admin.announce 必须登记在 PermissionCode 枚举中，否则无法在角色编辑页授权");

        Method announce = AdminResource.class.getDeclaredMethod("sendAnnounce", String.class);
        RequirePermission rp = announce.getAnnotation(RequirePermission.class);
        assertNotNull(rp, "/admin/announce 必须声明 @RequirePermission");
        assertEquals("admin.announce", rp.value(),
                "全服广播是写操作，不得用只读的 admin.status 门控");
    }

    @Test
    @DisplayName("admin.users.manage 与 admin.announce 都不在 MODERATOR 的只读权限里")
    void testWritePermissionsAreNotReadOnlyCodes() {
        // 这两个码是本次新增/沿用的写权限，必须与只读码区分开，否则收紧无意义。
        assertFalse(PLAYER_WRITE_PERM.equals(READ_ONLY_PERM), "写权限码不能等于只读码");
        assertFalse("admin.announce".equals(READ_ONLY_PERM), "公告权限不能等于只读码");
        // 只读码本身仍应存在且显示名仍表达「查看」语义。
        PermissionCode status = PermissionCode.fromCode(READ_ONLY_PERM);
        assertNotNull(status, "admin.status 必须保留（大量只读接口依赖它）");
        assertTrue(status.getName().startsWith("查看"),
                "admin.status 的显示名应表达只读语义，实际: " + status.getName());
    }

    // ==================== 不变量 3：admin.status 的指令必须逐个审核过 ====================

    /**
     * 允许使用只读权限 {@code admin.status} 的指令白名单。
     * <p>
     * 新增指令若也想用 {@code admin.status}，本测试会失败 —— 这是刻意的：
     * 请先确认它是纯读取（不写任何玩家/服务器状态），再把类名加进来。
     */
    private static final List<Class<? extends Command>> ADMIN_STATUS_ALLOWLIST = List.of(
            com.mtxgdn.onebot.command.admin.StatusCommand.class,
            com.mtxgdn.onebot.command.admin.PlayersListCommand.class,
            com.mtxgdn.onebot.command.admin.PlayerDetailCommand.class,
            com.mtxgdn.onebot.command.admin.ItemsQueryCommand.class,
            // 能量管理：指令级权限只用于「查看」，写操作在指令内部按 action 二次校验。
            com.mtxgdn.onebot.command.admin.EnergyAdminCommand.class);

    @Test
    @DisplayName("不变量: 用 admin.status 的指令必须在白名单内（新增指令需先确认是纯读取）")
    void testAdminStatusCommandsAreAllowlisted() {
        List<String> unexpected = new ArrayList<>();
        for (Command cmd : CommandRegistry.getAllUnique()) {
            if (!READ_ONLY_PERM.equals(cmd.getPermission())) continue;
            if (!ADMIN_STATUS_ALLOWLIST.contains(cmd.getClass())) {
                unexpected.add(cmd.getClass().getName());
            }
        }
        assertTrue(unexpected.isEmpty(),
                "以下指令用只读权限 admin.status 门控但不在白名单内。若确为纯读取，"
                        + "请把类名加入 AdminWritePermissionTest.ADMIN_STATUS_ALLOWLIST；"
                        + "若会写数据，请改用 admin.users.manage 等写权限: " + unexpected);
    }

    @Test
    @DisplayName("不变量: 白名单里的指令都仍然注册着（防止白名单腐烂）")
    void testAllowlistEntriesStillExist() {
        for (Class<? extends Command> type : ADMIN_STATUS_ALLOWLIST) {
            Command cmd = commandOf(type); // 找不到会抛 AssertionError
            assertEquals(READ_ONLY_PERM, cmd.getPermission(),
                    type.getSimpleName() + " 已不在白名单描述的状态，请同步更新白名单");
        }
    }

    private static void assertPermissionOf(Class<? extends Command> type, String expected, String why) {
        Command cmd = commandOf(type);
        assertEquals(expected, cmd.getPermission(),
                type.getSimpleName() + " 的权限码应为 " + expected + "（" + why + "）");
    }
}
