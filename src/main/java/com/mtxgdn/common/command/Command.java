package com.mtxgdn.common.command;

import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.permission.PermissionService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            Map.entry("管理", 11)
    );

    private final String[] names;
    private final String description;
    private final String usage;
    private final String category;
    private final String permission;
    private final boolean privateOnly;

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
                      String category, String permission, boolean privateOnly) {
        this.names = names;
        this.description = description;
        this.usage = usage;
        this.category = category;
        this.permission = permission;
        this.privateOnly = privateOnly;
        CommandRegistry.register(this);
    }

    protected Command(String[] names, String description, String usage,
                      String category, String permission) {
        this(names, description, usage, category, permission, false);
    }

    protected Command(String[] names, String description, String usage, String category) {
        this(names, description, usage, category, null, false);
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
            ctx.reply("权限不足");
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
            ctx.reply("用法: " + getUsage());
        }
    }

    /** 未知子命令时的默认行为。覆写此方法来自定义。 */
    protected void onUnknown(CommandContext ctx, PlayerInfo p, String sub, String[] parts) {
        ctx.reply("未知子命令: " + sub + "\n用法: " + getUsage());
    }

    // ==================== Getters ====================

    public String[] getNames() {
        return names;
    }

    public String getDescription() {
        return description;
    }

    public String getUsage() {
        return usage;
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

    public boolean shouldShowInHelp(Long userId) {
        if (permission == null) {
            return true;
        }
        if (userId == null) {
            return false;
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
