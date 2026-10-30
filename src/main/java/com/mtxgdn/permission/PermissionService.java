package com.mtxgdn.permission;

import com.mtxgdn.db.PermissionDAO;
import com.mtxgdn.util.AppConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限服务 - 管理权限组（角色）和权限的分配与校验。
 *
 * 概念说明：
 * - 权限组（PermissionGroup）：一组权限的集合，拥有名称(唯一标识)、显示名称、等级(用于排序和层级判断)。
 *   原有系统中的"角色"(role)在逻辑上等同于权限组。
 * - 权限(Permission)：细粒度的操作许可，如 "game.player.info"、"admin.users.manage"。
 *   系统内置权限由 {@link PermissionCode} 枚举定义，插件可注册自定义权限。
 * - 用户可以属于多个权限组，其有效权限 = 所有组权限的并集 + 直接分配的权限。
 *
 * <p><b>本类只剩业务逻辑</b>：角色层级判断、默认权限集合、继承展开、缓存与校验都在这里；
 * 所有 SQL 与连接管理搬到了 {@link PermissionDAO}（每个方法都有"收调用方连接"的重载，
 * 事务内必须用那一版，否则 SQLite 会抛 {@code SQLITE_BUSY_SNAPSHOT}）。
 * 这次搬运是纯结构重构：公开 API、权限语义、异常文案、缓存失效时机一律未变。</p>
 */
public class PermissionService {

    private static final int DEFAULT_ROLE_LEVEL = AppConfig.getInt("permission.default.role.level", 10);

    /**
     * 权限表的读写入口。
     *
     * <p>无状态（每条语句自己开/用连接），因此一个共享实例就够。</p>
     */
    private static final PermissionDAO PERMISSION_DAO = new PermissionDAO();

    // ==================== 默认权限组定义（系统内置，首次初始化时写入DB） ====================

    private static final Map<String, Integer> DEFAULT_GROUP_HIERARCHY = new LinkedHashMap<>();
    static {
        DEFAULT_GROUP_HIERARCHY.put("SUPER_ADMIN", 100);
        DEFAULT_GROUP_HIERARCHY.put("ADMIN", 80);
        DEFAULT_GROUP_HIERARCHY.put("MODERATOR", 50);
        DEFAULT_GROUP_HIERARCHY.put("PLAYER", 10);
        DEFAULT_GROUP_HIERARCHY.put("GUEST", 0);
    }

    /**
     * 获取默认的权限组→权限码映射（使用字符串而非枚举，以兼容插件权限）。
     */
    private static Map<String, Set<String>> buildDefaultGroupPermissions() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        Set<String> allCodes = new HashSet<>();
        for (PermissionCode pc : PermissionCode.values()) {
            allCodes.add(pc.getCode());
        }

        // PLAYER: 所有 game.* 和 qq.* 权限（不含 admin.*、不含 qq.command.admin、不含发放类权限）
        Set<String> playerPerms = new HashSet<>();
        for (PermissionCode pc : PermissionCode.values()) {
            String code = pc.getCode();
            if ((code.startsWith("game.") || code.startsWith("qq."))
                    && !code.startsWith("admin.")
                    && !code.equals("qq.command.admin")
                    && !code.equals("game.item.add")) { // 发放物品属于管理员操作，玩家不可自刷
                playerPerms.add(code);
            }
        }

        // MODERATOR: PLAYER + 管理查看权限
        Set<String> moderatorPerms = new HashSet<>(playerPerms);
        moderatorPerms.add(PermissionCode.ADMIN_LOGIN.getCode());
        moderatorPerms.add(PermissionCode.ADMIN_STATUS.getCode());
        moderatorPerms.add(PermissionCode.ADMIN_LOGS_VIEW.getCode());
        moderatorPerms.add(PermissionCode.QQ_COMMAND_ADMIN.getCode());
        moderatorPerms.add(PermissionCode.ADMIN_BLACKLIST_VIEW.getCode());

        // ADMIN: 全部权限，但不含最高管理权限
        Set<String> adminPerms = new HashSet<>(allCodes);
        adminPerms.remove(PermissionCode.ADMIN_ROLES_MANAGE.getCode());
        adminPerms.remove(PermissionCode.ADMIN_USERS_MANAGE.getCode());
        adminPerms.remove(PermissionCode.ADMIN_DATABASE_CLEAR_PLAYERS.getCode());
        adminPerms.remove(PermissionCode.ADMIN_DATABASE_RESET_ALL.getCode());

        // GUEST: 仅基本查询
        Set<String> guestPerms = new HashSet<>();
        guestPerms.add(PermissionCode.QQ_COMMAND_BASIC.getCode());

        result.put("SUPER_ADMIN", new HashSet<>(allCodes));
        result.put("ADMIN", adminPerms);
        result.put("MODERATOR", moderatorPerms);
        result.put("PLAYER", playerPerms);
        result.put("GUEST", guestPerms);
        return result;
    }

    // ==================== 运行时缓存（从DB加载） ====================

    /** 权限组等级映射：groupName → level（从DB加载） */
    private static volatile Map<String, Integer> groupHierarchy = null;

    /** 权限组权限映射：groupName → Set&lt;permissionCode&gt;（从DB加载，字符串集合兼容插件权限） */
    private static volatile Map<String, Set<String>> groupPermissionCodes = null;

    /** 权限组显示名称映射：groupName → displayName */
    private static volatile Map<String, String> groupDisplayNames = null;

    /** 是否已初始化（调用过 initDefaultData / refreshGroupCache） */
    private static volatile boolean initialized = false;

    /**
     * 用户有效权限码的短期缓存：userId → (权限码集合, 过期时间)。
     *
     * <p><b>为什么需要它</b>：{@code hasPermission} 每次调用要跑 <b>3 条 SQL</b> ——
     * 一次查角色（{@code getUserRoles}）、一次查直接授予的内置权限、
     * 一次再查同一张 {@code user_permissions} 表拿插件权限码（两次查的是同一张表、同一批行，
     * 只是过滤条件不同）。而 {@code hasPermission} 会被
     * {@code PermissionFilter}（每个 REST 请求）、每个 WS 动作、每条 QQ 指令调用一次 ——
     * 也就是说一次 API 请求光"校验权限"就要 3 次数据库往返。</p>
     *
     * <p>权限变更频率远低于读取频率，因此这里按用户缓存有效权限集合，
     * TTL 内直接命中。所有会改动权限的入口都会显式失效缓存
     * （见 {@link #invalidatePermissionCache()}），所以 TTL 只用于兜底
     * "别的地方直接改了库"这种非正常路径。</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, CachedPermissions> PERMISSION_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 缓存有效期（毫秒）：默认 5 秒，可通过 permission.cache_ttl_ms 调整，0 表示禁用缓存。 */
    private static final long PERMISSION_CACHE_TTL_MS =
            Math.max(0, com.mtxgdn.util.AppConfig.getInt("permission.cache_ttl_ms", 5000));

    /** 一个用户的权限缓存项。 */
    private static final class CachedPermissions {
        final Set<String> codes;
        final long expiresAt;

        CachedPermissions(Set<String> codes, long expiresAt) {
            this.codes = codes;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * 清空权限缓存。
     *
     * <p>所有权限写操作（分配/移除角色、授予/撤销权限、改权限组）都必须调用它，
     * 否则管理员刚改完的权限要等 TTL 到期才生效 —— 撤销权限尤其不能等。</p>
     */
    public static void invalidatePermissionCache() {
        PERMISSION_CACHE.clear();
    }

    /** 仅失效单个用户（当前实现直接整体清空，代价可忽略）。 */
    public static void invalidatePermissionCache(long userId) {
        PERMISSION_CACHE.remove(userId);
    }

    /**
     * 换库时丢掉全部权限缓存，等下次访问重新从<b>新库</b>加载。
     *
     * <p>只置标志位、不立刻读库：换库发生在 {@code DatabaseManager.init()} 内部，
     * 那时立即 {@code refreshGroupCache()} 会在一个尚未稳定的时机触发一整套查询。
     * 推迟到下一次 {@link #ensureLoaded()} 更安全，顺序也更可控。</p>
     *
     * <p>由 {@code ServiceRegistry} 在换库监听里调用 —— 本类不自己登记，
     * 否则要在服务层 import {@code DatabaseManager}，而
     * {@code PermissionDaoExtractionTest.serviceLayerContainsNoJdbc} 明确禁止这件事。</p>
     */
    public static synchronized void invalidateAllCachesForNewDatabase() {
        PERMISSION_CACHE.clear();
        groupPermissionCodes = null;
        groupDisplayNames = null;
        initialized = false;
    }

    /**
     * 确保运行时缓存已加载。
     */
    private static synchronized void ensureLoaded() {
        if (!initialized) {
            refreshGroupCache();
            initialized = true;
        }
    }

    /**
     * 从数据库重新加载所有权限组数据（等级、显示名、权限映射）。
     * 如果数据库中没有任何权限组，则使用默认定义初始化。
     */
    public static synchronized void refreshGroupCache() {
        Map<String, Integer> hierarchy = new LinkedHashMap<>();
        Map<String, String> displayNames = new LinkedHashMap<>();
        Map<String, Set<String>> perms = new LinkedHashMap<>();

        try {
            PermissionDAO.GroupCatalog catalog = PERMISSION_DAO.loadGroupCatalog();
            for (PermissionDAO.RoleRow row : catalog.roles()) {
                hierarchy.put(row.name(), row.level());
                displayNames.put(row.name(), row.displayName());
                perms.put(row.name(), new HashSet<>());
            }
            // 组→权限映射：只挂到"库里真实存在的组"上（孤立的 role_permissions 行一律忽略）
            for (Map.Entry<String, Set<String>> entry : catalog.rolePermissions().entrySet()) {
                Set<String> set = perms.get(entry.getKey());
                if (set != null) {
                    set.addAll(entry.getValue());
                }
            }
        } catch (RuntimeException e) {
            // 文案与重构前逐字一致：打印的是底层 SQL 失败原因
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("[PermissionService] 从DB加载权限组失败，使用默认配置: " + cause.getMessage());
            // 回退到默认配置
            hierarchy = new LinkedHashMap<>(DEFAULT_GROUP_HIERARCHY);
            for (String name : hierarchy.keySet()) {
                displayNames.put(name, name);
            }
            perms = buildDefaultGroupPermissions();
        }

        // 如果数据库中没有任何权限组，使用默认配置但不写入DB（initDefaultData 会写入）
        if (hierarchy.isEmpty()) {
            hierarchy = new LinkedHashMap<>(DEFAULT_GROUP_HIERARCHY);
            for (String name : hierarchy.keySet()) {
                displayNames.put(name, name);
            }
            perms = buildDefaultGroupPermissions();
        } else {
            Map<String, Set<String>> defaultPerms = buildDefaultGroupPermissions();
            for (Map.Entry<String, Set<String>> entry : defaultPerms.entrySet()) {
                String groupName = entry.getKey();
                Set<String> defaultGroupPerms = entry.getValue();
                Set<String> existingPerms = perms.get(groupName);
                if (existingPerms != null) {
                    for (String permCode : defaultGroupPerms) {
                        if (!existingPerms.contains(permCode)) {
                            existingPerms.add(permCode);
                            try {
                                PERMISSION_DAO.insertRolePermissionIfAbsent(
                                        groupName, permCode, "写入权限组权限失败");
                            } catch (RuntimeException ignored) {
                                // 与重构前一致：补默认权限失败只跳过这一条，不影响缓存加载
                            }
                        }
                    }
                }
            }
        }

        groupHierarchy = hierarchy;
        groupDisplayNames = displayNames;
        groupPermissionCodes = perms;
        // 权限组数据变了，用户的有效权限缓存必须一起作废，
        // 否则刚被撤掉的权限在 TTL 内仍然有效。
        PERMISSION_CACHE.clear();
    }

    // ==================== 插件权限 ====================

    /** 插件注册的权限。key=权限码，value=权限信息 */
    private static final Map<String, PluginPermissionInfo> pluginPermissions = new LinkedHashMap<>();

    /** 插件权限信息。 */
    public static class PluginPermissionInfo {
        public final String code;
        public final String name;
        public final String category;
        PluginPermissionInfo(String code, String name, String category) {
            this.code = code;
            this.name = name;
            this.category = category;
        }
    }

    /**
     * 注册一个插件自定义权限。在插件 onLoad/onEnable 阶段调用。
     * 权限码会写入数据库 permissions 表，并可用于 hasPermission / assignPermission 等所有检查。
     * 注意：插件权限不自动分配给任何权限组，需管理员手动分配。
     */
    public static void registerPluginPermission(String code, String name, String category) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("权限码不能为空");
        }
        if (PermissionCode.fromCode(code) != null || pluginPermissions.containsKey(code)) {
            throw new IllegalArgumentException("权限码已存在: " + code);
        }
        pluginPermissions.put(code, new PluginPermissionInfo(code, name, category));
        PERMISSION_DAO.insertPermissionIfAbsent(code, name,
                category != null ? category : "插件扩展", "注册插件权限失败: " + code);
        // 新注册的插件权限会影响"直接授予的权限码是否算数"，缓存必须作废
        invalidatePermissionCache();
    }

    /** 判断一个权限码是否为已注册的插件权限。 */
    public static boolean isPluginPermission(String code) {
        return pluginPermissions.containsKey(code);
    }

    /** 获取所有插件注册的权限。 */
    public static Map<String, PluginPermissionInfo> getPluginPermissions() {
        return Collections.unmodifiableMap(pluginPermissions);
    }

    /** 判断权限码是否有效（内置枚举、插件注册或数据库中已存在）。 */
    public static boolean isValidPermissionCode(String code) {
        if (PermissionCode.fromCode(code) != null || pluginPermissions.containsKey(code)) {
            return true;
        }
        try {
            return PERMISSION_DAO.existsPermissionCode(code);
        } catch (RuntimeException ignored) {
            // 与重构前一致：查库失败当作"没有这个权限码"，不向上抛
        }
        return false;
    }

    // ==================== 权限组查询 ====================

    /** 获取所有权限组的等级映射（name → level，按等级降序）。 */
    public static Map<String, Integer> getRoleHierarchy() {
        ensureLoaded();
        return Collections.unmodifiableMap(groupHierarchy);
    }

    /** 获取所有权限组的名称集合。 */
    public static Set<String> getRoleNames() {
        ensureLoaded();
        return Collections.unmodifiableSet(groupHierarchy.keySet());
    }

    /** 获取权限组的显示名称。 */
    public static String getGroupDisplayName(String groupName) {
        ensureLoaded();
        return groupDisplayNames.getOrDefault(groupName, groupName);
    }

    /** 获取所有权限组信息（名称、显示名、等级、权限数量）。 */
    public static List<Map<String, Object>> getAllGroups() {
        ensureLoaded();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : groupHierarchy.entrySet()) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("name", entry.getKey());
            group.put("displayName", groupDisplayNames.getOrDefault(entry.getKey(), entry.getKey()));
            group.put("level", entry.getValue());
            Set<String> perms = groupPermissionCodes.getOrDefault(entry.getKey(), Collections.emptySet());
            group.put("permissionCount", perms.size());
            group.put("permissions", new ArrayList<>(perms));
            // 标记是否为系统内置组
            group.put("system", DEFAULT_GROUP_HIERARCHY.containsKey(entry.getKey()));
            result.add(group);
        }
        return result;
    }

    /** 获取默认权限组名称。 */
    public static String getDefaultRoleName() {
        ensureLoaded();
        for (Map.Entry<String, Integer> entry : groupHierarchy.entrySet()) {
            if (entry.getValue() <= DEFAULT_ROLE_LEVEL) {
                return entry.getKey();
            }
        }
        return "PLAYER";
    }

    // ==================== 用户角色/权限组管理 ====================

    public static List<String> getUserRoles(long userId) {
        return PERMISSION_DAO.findRoleNamesByUserId(userId);
    }

    public static String getHighestRole(long userId) {
        ensureLoaded();
        List<String> roles = getUserRoles(userId);
        String highest = null;
        int highestLevel = -1;
        for (String role : roles) {
            Integer level = groupHierarchy.get(role);
            if (level != null && level > highestLevel) {
                highestLevel = level;
                highest = role;
            }
        }
        return highest;
    }

    public static int getHighestRoleLevel(long userId) {
        ensureLoaded();
        String role = getHighestRole(userId);
        if (role == null) {
            String defaultRole = getDefaultRoleName();
            return groupHierarchy.getOrDefault(defaultRole, 0);
        }
        return groupHierarchy.getOrDefault(role, 0);
    }

    // ==================== 权限查询 ====================

    /**
     * 获取用户单独分配的权限码（仅内置枚举类型）。
     */
    public static Set<PermissionCode> getUserDirectPermissions(long userId) {
        Set<PermissionCode> result = EnumSet.noneOf(PermissionCode.class);
        for (String code : PERMISSION_DAO.findDirectPermissionCodes(userId, "查询用户单独权限失败")) {
            PermissionCode pc = PermissionCode.fromCode(code);
            if (pc != null) {
                result.add(pc);
            }
        }
        return result;
    }

    /**
     * 获取用户单独分配的插件权限码字符串集合。
     */
    public static Set<String> getUserPluginPermissionCodes(long userId) {
        Set<String> result = new HashSet<>();
        for (String code : PERMISSION_DAO.findDirectPermissionCodes(userId, "查询用户插件权限失败")) {
            if (pluginPermissions.containsKey(code)) {
                result.add(code);
            }
        }
        return result;
    }

    /**
     * 获取用户权限组继承的所有权限码字符串（包括内置和插件权限）。
     */
    public static Set<String> getUserGroupPermissionCodes(long userId) {
        ensureLoaded();
        Set<String> result = new HashSet<>();
        List<String> roles = getUserRoles(userId);
        if (roles.isEmpty()) {
            // 无角色时使用默认角色权限
            Set<String> defaultPerms = groupPermissionCodes.get(getDefaultRoleName());
            if (defaultPerms != null) result.addAll(defaultPerms);
        } else {
            for (String role : roles) {
                Set<String> rolePerms = groupPermissionCodes.get(role);
                if (rolePerms != null) {
                    result.addAll(rolePerms);
                }
            }
        }
        return result;
    }

    /**
     * 获取用户全部有效权限的内置枚举集合（角色继承 + 单独分配）。
     * 保持向后兼容。
     */
    public static Set<PermissionCode> getUserPermissions(long userId) {
        ensureLoaded();
        Set<PermissionCode> permissions = EnumSet.noneOf(PermissionCode.class);

        // 从权限组继承的枚举权限
        for (String code : getUserGroupPermissionCodes(userId)) {
            PermissionCode pc = PermissionCode.fromCode(code);
            if (pc != null) {
                permissions.add(pc);
            }
        }

        // 合并用户单独分配的枚举权限
        permissions.addAll(getUserDirectPermissions(userId));

        return permissions;
    }

    /**
     * 获取用户全部有效权限码（字符串，包括内置和插件权限）。
     */
    public static Set<String> getAllUserPermissionCodes(long userId) {
        Set<String> result = new HashSet<>();
        result.addAll(getUserGroupPermissionCodes(userId));
        result.addAll(getUserPluginPermissionCodes(userId));
        for (PermissionCode pc : getUserDirectPermissions(userId)) {
            result.add(pc.getCode());
        }
        return result;
    }

    /**
     * 检查用户是否拥有指定权限。
     *
     * <p>走 {@link #getEffectivePermissionCodes(long)} 的缓存，避免每次调用都跑 3 条 SQL。
     * 权限判定逻辑本身没有变化：角色继承（含插件权限） ∪ 直接授予，任一命中即为有权。</p>
     */
    public static boolean hasPermission(long userId, String permissionCode) {
        if (permissionCode == null || permissionCode.isEmpty()) {
            return false;
        }
        return getEffectivePermissionCodes(userId).contains(permissionCode);
    }

    /**
     * 取用户的有效权限码集合（角色继承 ∪ 直接授予），带短期缓存。
     *
     * <p>原来 {@code hasPermission} 分三次查询、其中两次查的是同一张
     * {@code user_permissions} 表（一次取内置枚举、一次取插件权限码）。
     * 这里合并成"取一次全量码集合再在内存里判断"，
     * 单次未命中时是 2 条 SQL，命中时是 0 条。</p>
     */
    static Set<String> getEffectivePermissionCodes(long userId) {
        if (PERMISSION_CACHE_TTL_MS <= 0) {
            return computeEffectivePermissionCodes(userId);
        }
        long now = System.currentTimeMillis();
        CachedPermissions cached = PERMISSION_CACHE.get(userId);
        if (cached != null && cached.expiresAt > now) {
            return cached.codes;
        }
        Set<String> codes = computeEffectivePermissionCodes(userId);
        PERMISSION_CACHE.put(userId, new CachedPermissions(codes, now + PERMISSION_CACHE_TTL_MS));
        return codes;
    }

    private static Set<String> computeEffectivePermissionCodes(long userId) {
        Set<String> result = new HashSet<>(getUserGroupPermissionCodes(userId));
        // user_permissions 只查一次：同时取出内置权限码与插件权限码
        for (String code : queryDirectPermissionCodes(userId)) {
            result.add(code);
        }
        return Collections.unmodifiableSet(result);
    }

    /** 读取 user_permissions 里该用户的全部权限码（内置与插件混在一起）。 */
    private static Set<String> queryDirectPermissionCodes(long userId) {
        Set<String> result = new HashSet<>();
        for (String code : PERMISSION_DAO.findDirectPermissionCodes(userId, "查询用户权限失败")) {
            // 保持原有语义：只有"内置枚举里存在的"或"已注册插件权限"才算数，
            // 手工塞进库里的野码一律忽略（否则 hasPermission 的判定范围会比以前宽）。
            if (code != null
                    && (PermissionCode.fromCode(code) != null || pluginPermissions.containsKey(code))) {
                result.add(code);
            }
        }
        return result;
    }

    // ==================== 用户角色分配 ====================

    public static void assignRole(long userId, String roleName) {
        ensureLoaded();
        if (!groupHierarchy.containsKey(roleName)) {
            throw new IllegalArgumentException("未知权限组: " + roleName);
        }

        PERMISSION_DAO.insertUserRoleIfAbsent(userId, roleName);
        invalidatePermissionCache(userId);
    }

    /**
     * 移除用户的某个角色。
     *
     * <p><b>修复说明</b>：这里原来有一道
     * {@code if (getHighestRoleLevel(userId) <= groupHierarchy.getOrDefault(roleName, 0))}
     * 的判断，方向是反的 —— {@code getHighestRoleLevel} 是<b>目标用户</b>的最高角色等级，
     * 它必然 &ge; 该用户持有的任何角色等级，所以条件只在"要移除的正好是他的最高角色"时成立。
     * 也就是说：管理员给一个同时持有 admin(100) 和 vip(5) 的用户摘掉 vip，条件
     * {@code 100 <= 5} 不成立，角色<b>纹丝不动</b>，而调用方还照样回 200 成功。</p>
     *
     * <p>而且这里根本没有"操作者"参数 —— 授权判断本来就该由调用方的
     * {@code @RequirePermission} 负责，服务层不该拿目标用户自己的等级去猜权限。
     * 因此删掉这道守卫，让本方法成为纯数据操作；"用户是否真的有这个角色""能不能移除默认角色"
     * 由 {@code AdminUserResource} 显式检查并给出诚实的响应。</p>
     */
    public static void removeRole(long userId, String roleName) {
        ensureLoaded();
        PERMISSION_DAO.deleteUserRole(userId, roleName);
        invalidatePermissionCache(userId);
    }

    public static void assignDefaultRole(long userId) {
        assignRole(userId, getDefaultRoleName());
    }

    /**
     * 给用户单独分配一个权限码
     */
    public static void assignPermission(long userId, String permissionCode) {
        if (!isValidPermissionCode(permissionCode)) {
            throw new IllegalArgumentException("未知权限码: " + permissionCode);
        }
        PERMISSION_DAO.insertUserPermissionIfAbsent(userId, permissionCode);
        invalidatePermissionCache(userId);
    }

    /**
     * 移除用户单独分配的权限码
     */
    public static void removePermission(long userId, String permissionCode) {
        PERMISSION_DAO.deleteUserPermission(userId, permissionCode);
        invalidatePermissionCache(userId);
    }

    /** 获取权限组的默认权限码列表（字符串集合）。 */
    public static Set<String> getGroupPermissionCodes(String groupName) {
        ensureLoaded();
        return Collections.unmodifiableSet(groupPermissionCodes.getOrDefault(groupName, Collections.emptySet()));
    }

    /** 向后兼容：获取权限组的默认权限（枚举形式）。 */
    public static Set<PermissionCode> getRoleDefaultPermissions(String roleName) {
        Set<PermissionCode> result = EnumSet.noneOf(PermissionCode.class);
        for (String code : getGroupPermissionCodes(roleName)) {
            PermissionCode pc = PermissionCode.fromCode(code);
            if (pc != null) {
                result.add(pc);
            }
        }
        return result;
    }

    // ==================== 权限组 CRUD ====================

    /**
     * 创建新的权限组。
     * @param name 权限组唯一名称
     * @param displayName 显示名称
     * @param level 等级（数值越大权限越高）
     * @throws IllegalArgumentException 如果名称已存在
     */
    public static void createGroup(String name, String displayName, int level) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("权限组名称不能为空");
        }
        ensureLoaded();
        if (groupHierarchy.containsKey(name)) {
            throw new IllegalArgumentException("权限组已存在: " + name);
        }
        PERMISSION_DAO.createRole(name, displayName != null ? displayName : name, level);
        refreshGroupCache();
    }

    /**
     * 更新权限组的显示名称和等级。
     */
    public static void updateGroup(String name, String displayName, int level) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("权限组名称不能为空");
        }
        ensureLoaded();
        if (!groupHierarchy.containsKey(name)) {
            throw new IllegalArgumentException("权限组不存在: " + name);
        }
        PERMISSION_DAO.updateRole(name, displayName != null ? displayName : name, level);
        refreshGroupCache();
    }

    /**
     * 删除权限组。
     *
     * <p>"删组权限映射 → 删用户关联 → 删组定义"这三步必须原子，事务边界在
     * {@link PermissionDAO#deleteGroupCascade(String)} 里（顺序固定、失败整笔回滚）。</p>
     *
     * @param name 权限组名称
     * @throws IllegalArgumentException 如果是系统内置权限组，或组不存在
     */
    public static void deleteGroup(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("权限组名称不能为空");
        }
        if (DEFAULT_GROUP_HIERARCHY.containsKey(name)) {
            throw new IllegalArgumentException("系统内置权限组不可删除: " + name);
        }
        PERMISSION_DAO.deleteGroupCascade(name);
        refreshGroupCache();
    }

    /**
     * 为权限组添加一个权限。
     */
    public static void addGroupPermission(String groupName, String permissionCode) {
        if (!isValidPermissionCode(permissionCode)) {
            throw new IllegalArgumentException("未知权限码: " + permissionCode);
        }
        ensureLoaded();
        if (!groupHierarchy.containsKey(groupName)) {
            throw new IllegalArgumentException("权限组不存在: " + groupName);
        }
        PERMISSION_DAO.insertRolePermissionIfAbsent(groupName, permissionCode, "添加权限组权限失败");
        refreshGroupCache();
    }

    /**
     * 从权限组移除一个权限。
     */
    public static void removeGroupPermission(String groupName, String permissionCode) {
        PERMISSION_DAO.deleteRolePermission(groupName, permissionCode);
        refreshGroupCache();
    }

    /**
     * 设置权限组的完整权限列表（覆盖现有权限）。
     *
     * <p>"先清空、再批量插入"必须在同一个事务里，事务边界在
     * {@link PermissionDAO#replaceRolePermissions(String, Set)} 里。</p>
     */
    public static void setGroupPermissions(String groupName, Set<String> permissionCodes) {
        ensureLoaded();
        if (!groupHierarchy.containsKey(groupName)) {
            throw new IllegalArgumentException("权限组不存在: " + groupName);
        }
        for (String code : permissionCodes) {
            if (!isValidPermissionCode(code)) {
                throw new IllegalArgumentException("未知权限码: " + code);
            }
        }
        PERMISSION_DAO.replaceRolePermissions(groupName, permissionCodes);
        refreshGroupCache();
    }

    // ==================== 用户列表查询 ====================

    public static List<Map<String, Object>> getAllUsersWithRoles() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<PermissionDAO.UserRoleRow> rows = PERMISSION_DAO.findAllUserRoleRows();

        // 先把"账号 → 角色列表"聚合起来。LEFT JOIN 保证没有任何角色的账号也有一行
        // （roleName 为 null），这种账号同样要出现在列表里。
        Map<Long, Map<String, Object>> userMap = new LinkedHashMap<>();
        for (PermissionDAO.UserRoleRow row : rows) {
            long userId = row.userId();
            userMap.putIfAbsent(userId, new HashMap<>());
            Map<String, Object> userEntry = userMap.get(userId);
            userEntry.put("id", userId);
            userEntry.put("username", row.username());
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) userEntry.computeIfAbsent("roles", k -> new ArrayList<>());
            if (row.roleName() != null) {
                roles.add(row.roleName());
            }
        }

        // 再逐账号补上"直接授予的权限码"（内置枚举 + 插件权限）
        for (Map.Entry<Long, Map<String, Object>> entry : userMap.entrySet()) {
            Set<PermissionCode> directPerms = getUserDirectPermissions(entry.getKey());
            List<String> permCodes = new ArrayList<>();
            for (PermissionCode pc : directPerms) {
                permCodes.add(pc.getCode());
            }
            permCodes.addAll(getUserPluginPermissionCodes(entry.getKey()));
            entry.getValue().put("directPermissions", permCodes);
            result.add(entry.getValue());
        }
        return result;
    }

    // ==================== 初始化 ====================

    public static void initDefaultData() {
        // 创建用户单独权限表
        PERMISSION_DAO.createUserPermissionsTable();

        // 初始化默认权限组
        for (Map.Entry<String, Integer> entry : DEFAULT_GROUP_HIERARCHY.entrySet()) {
            PERMISSION_DAO.insertRoleIfAbsent(entry.getKey(), entry.getValue(), entry.getKey());
        }

        // 初始化权限码
        for (PermissionCode pc : PermissionCode.values()) {
            PERMISSION_DAO.insertPermissionIfAbsent(pc.getCode(), pc.getName(), pc.getCategory(),
                    "初始化权限数据失败");
        }

        // 初始化权限组→权限映射
        Map<String, Set<String>> defaultPerms = buildDefaultGroupPermissions();
        for (Map.Entry<String, Set<String>> entry : defaultPerms.entrySet()) {
            for (String permCode : entry.getValue()) {
                PERMISSION_DAO.insertRolePermissionIfAbsent(entry.getKey(), permCode, "初始化角色权限数据失败");
            }
        }

        // 加载到运行时缓存
        refreshGroupCache();
    }
}
