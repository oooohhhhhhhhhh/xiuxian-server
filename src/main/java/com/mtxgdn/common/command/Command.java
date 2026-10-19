package com.mtxgdn.common.command;

import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.permission.PermissionService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.mtxgdn.i18n.I18n;

public abstract class Command {

    /**
     * 分类排序优先级表。数字越小越靠前，未列出的分类默认排在最后。
     * 新增分类只需在此 map 中添加一行即可，无需改 HelpCommand。
     */
    private static final Map<String, Integer> CATEGORY_ORDER = Map.ofEntries(
            Map.entry("账号", 1),
            Map.entry("我的角色", 2),
            Map.entry("修炼", 3),
            Map.entry("战斗", 4),
            Map.entry("背包", 5),
            Map.entry("探索", 6),
            Map.entry("坊市", 7),
            Map.entry("宗门", 8),
            Map.entry("经济", 9),
            Map.entry("社交", 10),
            Map.entry("管理", 11),
            Map.entry("实用功能", 12)
    );

    private final String[] names;
    private final String description;
    private final String usage;
    private final String category;
    private final String permission;
    private final boolean privateOnly;
    private final boolean requiresLogin;

    /**
     * 描述/用法的 i18n key；为空表示不走语言包，直接用构造时给的中文字面量。
     *
     * <p>不是 final——{@link #setI18nKeys} 允许子类在 super(...) 之后补设，
     * 和 {@link #setModule} 一个路子。</p>
     */
    private String descriptionKey;
    private String usageKey;

    /**
     * 所属内置插件名（对应 config/plugins.yml 的 plugins.*，如 "farm"/"sect"/
     * "experimental_sect_war"）。null 表示不属于任何内置模块，不受模块启停门控。
     */
    private String module;

    /** 所有注册项（OneBot 子命令 + HTTP 路由）统一存储 */
    private final List<RouteDefinition> routes = new ArrayList<>();
    /** OneBot 子命令快速索引：subCommand → RouteDefinition */
    private final Map<String, RouteDefinition> subIndex = new LinkedHashMap<>();

    /**
     * OneBot 子命令处理器。
     * ctx: 指令上下文, p: 当前玩家, parts: 分词后的参数（parts[0] 是子命令名）
     */
    @FunctionalInterface
    public interface SubHandler {
        void handle(CommandContext ctx, PlayerInfo p, String[] parts);
    }

    protected Command(String[] names, String description, String usage,
                      String category, String permission, boolean privateOnly, boolean requiresLogin) {
        this.names = names;
        this.description = description;
        this.usage = usage;
        this.category = category;
        this.permission = permission;
        this.privateOnly = privateOnly;
        this.requiresLogin = requiresLogin;
        CommandRegistry.register(this);
    }

    protected Command(String[] names, String description, String usage,
                      String category, String permission, boolean privateOnly) {
        this(names, description, usage, category, permission, privateOnly, true);
    }

    protected Command(String[] names, String description, String usage,
                      String category, String permission) {
        this(names, description, usage, category, permission, false, true);
    }

    protected Command(String[] names, String description, String usage, String category) {
        this(names, description, usage, category, null, false, true);
    }

    // ==================== 内置模块门控 ====================

    /**
     * 标记本命令所属的内置玩法模块（如 "farm"），模块被停用时该命令将被禁用。
     * 在子类构造函数中调用 super(...) 之后调用。
     */
    protected void setModule(String module) {
        this.module = module;
    }

    /**
     * 给指令描述与用法指定 i18n key。key 缺词条时回落到构造时给的中文字面量，
     * 所以没有配套语言包的指令行为完全不变。
     *
     * <p>为什么不像 {@link #setModule} 那样塞进构造器：指令的 {@code names}
     * （玩家手打的命令名）永远不翻译，但描述/用法要翻译。把 key 单独拎出来，
     * 子类就只需在 super(...) 之后补一行 setI18nKeys(...)，
     * 构造函数那 7 个参数不用跟着变。</p>
     */
    protected void setI18nKeys(String descKey, String usageKey) {
        this.descriptionKey = descKey;
        this.usageKey = usageKey;
    }

    /** 所属内置玩法模块名；null 表示不属于任何内置模块。 */
    public String getModule() {
        return module;
    }

    /**
     * 模块门控：无模块或模块当前处于启用状态时返回 true。
     * 指令分发（CommandRegistry）与 HTTP 接口（ModuleGateFilter）统一以此判断。
     */
    public boolean isModuleEnabled() {
        return module == null
                || com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown(module);
    }

    // ==================== 统一注册 API（所有注册都走这里）====================

    /**
     * 注册一个路由项。OneBot 和 HTTP 共用此入口。
     * <ul>
     *   <li>{@link RouteDefinition#onebotOnly} — 仅 OneBot 子命令</li>
     *   <li>{@link RouteDefinition#httpOnlyGet} / {@code httpOnlyPost} — 仅 HTTP</li>
     *   <li>{@link RouteDefinition#get} / {@code post} — 双端同时注册</li>
     * </ul>
     */
    protected void addRoute(RouteDefinition route) {
        routes.add(route);
        if (!route.isHttpOnly() && route.getBotHandler() != null && route.getSubCommand() != null) {
            subIndex.put(route.getSubCommand().toLowerCase(), route);
        }
    }

    /**
     * 注册一个仅 OneBot 的子命令（等同于 {@code addRoute(RouteDefinition.onebotOnly(...))}）。
     */
    protected void registerSub(String name, SubHandler handler) {
        addRoute(RouteDefinition.onebotOnly(name, handler));
    }

    /**
     * 一次注册多个别名指向同一处理器。
     */
    protected void registerSub(String[] names, SubHandler handler) {
        for (String name : names) {
            registerSub(name, handler);
        }
    }

    // ==================== 默认 execute() ====================

    /**
     * 默认 execute：权限校验 + 子命令分发。
     * 如果子类不需要子命令分发（如简单的无参数指令），可以覆写 execute()。
     */
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        if (permission != null && !PermissionService.hasPermission(userId, permission)) {
            ctx.reply(I18n.t("game.hb36f9631"));
            return;
        }
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        String arg = ctx.getArg();
        if (arg == null || arg.isBlank()) {
            onDefault(ctx, p);
            return;
        }

        String[] parts = arg.trim().split("\\s+", 3);
        String sub = parts[0].toLowerCase();

        RouteDefinition route = subIndex.get(sub);
        if (route != null) {
            route.getBotHandler().handle(ctx, p, parts);
        } else {
            onUnknown(ctx, p, sub, parts);
        }
    }

    /** 无参数时的默认行为。覆写此方法来自定义。 */
    protected void onDefault(CommandContext ctx, PlayerInfo p) {
        if (!subIndex.isEmpty()) {
            ctx.reply(I18n.t("game.h4126d3a0", getUsage()));
        }
    }

    /** 未知子命令时的默认行为。覆写此方法来自定义。 */
    protected void onUnknown(CommandContext ctx, PlayerInfo p, String sub, String[] parts) {
        ctx.reply(I18n.t("game.he37e6dac", sub, getUsage()));
    }

    protected PlayerInfo requirePlayerInfo(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return null;
        if (permission != null && !ctx.checkPermission(permission)) return null;
        return ctx.requirePlayer(userId);
    }

    protected PlayerInfo requirePlayerInfo(CommandContext ctx, String perm) {
        Long userId = ctx.requireBinding();
        if (userId == null) return null;
        if (perm != null && !ctx.checkPermission(perm)) return null;
        return ctx.requirePlayer(userId);
    }

    protected Long parseLong(String s, long defaultValue) {
        if (s == null || s.isBlank()) return defaultValue;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    protected Integer parseInt(String s, Integer defaultValue) {
        if (s == null || s.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    protected String[] splitArgs(String arg, int limit) {
        if (arg == null || arg.isBlank()) return new String[0];
        return arg.trim().split("\\s+", limit);
    }

    // ==================== Getters ====================

    public String[] getNames() {
        return names;
    }

    public String getDescription() {
        return I18n.tOr(descriptionKey, description);
    }

    public String getUsage() {
        return I18n.tOr(usageKey, usage);
    }

    /**
     * 描述词条 key；为空表示这条指令还没接语言包。
     *
     * <p>暴露出来是为了让回归测试能核对"key 真的存在于语言包里"。
     * 只查 {@code getDescription()} 是不够的——key 缺失时会静默回落到中文字面量，
     * 看上去一切正常，实际上那条词条根本没被翻译过。</p>
     */
    public String getDescriptionKey() {
        return descriptionKey;
    }

    /** 用法词条 key；语义同 {@link #getDescriptionKey()}。 */
    public String getUsageKey() {
        return usageKey;
    }

    public String getCategory() {
        return category;
    }

    public String getPermission() {
        return permission;
    }

    public boolean isPrivateOnly() {
        return privateOnly;
    }

    /**
     * 返回此命令的 OneBot 子命令名列表，用于帮助信息中紧凑展示。
     */
    public List<String> getSubCommandNames() {
        List<String> names = new ArrayList<>();
        for (RouteDefinition r : routes) {
            if (!r.isHttpOnly() && r.getSubCommand() != null) {
                names.add(r.getSubCommand());
            }
        }
        return names;
    }

    public boolean shouldShowInHelp(Long userId) {
        if (!isModuleEnabled()) {
            return false; // 所属内置模块已停用，不在帮助中展示
        }
        if (userId == null) {
            return !requiresLogin;
        }
        if (permission == null) {
            return true;
        }
        return PermissionService.hasPermission(userId, permission);
    }

    public int getCategoryOrder() {
        return CATEGORY_ORDER.getOrDefault(category, Integer.MAX_VALUE);
    }

    /**
     * 返回此 Command 注册的所有 REST 路由（仅 HTTP 相关项）。
     * UnifiedRestResource 在启动时通过 CommandRegistry 收集。
     */
    public List<RouteDefinition> getRestEndpoints() {
        List<RouteDefinition> result = new ArrayList<>();
        for (RouteDefinition r : routes) {
            if (!r.isOnebotOnly()) {
                result.add(r);
            }
        }
        return Collections.unmodifiableList(result);
    }
}
