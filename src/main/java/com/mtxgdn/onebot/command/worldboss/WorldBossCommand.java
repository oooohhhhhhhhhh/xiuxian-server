package com.mtxgdn.onebot.command.worldboss;

import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.common.GameMessage;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.WorldBoss;
import com.mtxgdn.game.entity.WorldBossDamage;
import com.mtxgdn.game.service.WorldBossService;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import com.mtxgdn.i18n.I18n;

/**
 * 世界 Boss（B6）指令。
 *
 * <p>与 {@code /讨伐}（个人 PVE Boss）、{@code /boss}（团队 Raid Boss）的区别：
 * 这里打的是<b>全服共享血条</b>的那一只 —— 你打掉的血，别人再去看就少了那么多。</p>
 *
 * <ul>
 *   <li>{@code /世界boss} —— 列出当前池子（血量、预算、剩余时间、贡献榜前几名）</li>
 *   <li>{@code /世界boss 挑战 <序号|名称>} —— 挑战</li>
 *   <li>{@code /世界boss 排行 [序号]} —— 伤害贡献榜</li>
 *   <li>{@code /世界boss 刷新} —— 管理员强制重建整池</li>
 * </ul>
 */
public class WorldBossCommand extends Command {

    private static final WorldBossService worldBossService = ServiceRegistry.getWorldBossService();

    /** 管理员强制重建所需的权限码（与地图等世界数据的管理接口保持一致）。 */
    private static final String ADMIN_PERMISSION = "admin.database.access";

    public WorldBossCommand() {
        super(new String[]{"世界boss", "worldboss"},
                "世界Boss：全服共享血量的 Boss，按伤害分红",
                "/世界boss - 查看当前世界Boss\n"
                        + "/世界boss 挑战 <序号|名称> - 挑战世界Boss\n"
                        + "/世界boss 排行 [序号] - 查看伤害贡献榜\n"
                        + "/世界boss 刷新 - 重建整池（管理员）",
                "战斗");
        
        setI18nKeys("cmd.boss.desc", "cmd.boss.usage");
    setModule("world_boss");

        registerSub(new String[]{"挑战", "challenge", "打"}, this::handleChallenge);
        registerSub(new String[]{"排行", "rank", "贡献"}, this::handleRank);
        registerSub(new String[]{"刷新", "refresh"}, this::handleRefresh);
        registerSub(new String[]{"help", "帮助", "?"}, this::handleHelp);

        // 玩家侧 REST（与 /世界boss 等价）
        addRoute(RouteDefinition.get("worldboss/list", "game.player.info", this::handleListHttp));
        addRoute(RouteDefinition.post("worldboss/challenge", "game.pvp.challenge", this::handleChallengeHttp));

        // 管理台：查看整池与强制重建
        addRoute(RouteDefinition.get("admin/world-boss", ADMIN_PERMISSION, this::handleAdminListHttp));
        addRoute(RouteDefinition.post("admin/world-boss/refresh", ADMIN_PERMISSION, this::handleAdminRefreshHttp));
    }

    // ==================== OneBot ====================

    @Override
    protected void onDefault(CommandContext ctx, PlayerInfo p) {
        ctx.reply(buildPoolOverview(p));
    }

    @Override
    protected void onUnknown(CommandContext ctx, PlayerInfo p, String sub, String[] parts) {
        ctx.reply(buildPoolOverview(p));
    }

    private void handleHelp(CommandContext ctx, PlayerInfo p, String[] parts) {
        ctx.reply(getUsage());
    }

    private void handleChallenge(CommandContext ctx, PlayerInfo p, String[] parts) {
        String query = parts.length > 1 ? parts[1].trim() : "";
        if (query.isEmpty()) {
            ctx.reply(I18n.t("worldboss.hb2977150"));
            return;
        }
        Map<String, Object> result = worldBossService.challenge(
                p.getId(), WorldBossService.scope(), query, System.currentTimeMillis());
        String message = (String) result.get("message");
        Object log = result.get("battleLog");
        if (Boolean.TRUE.equals(result.get("success")) && log instanceof List<?> logs && !logs.isEmpty()) {
            StringBuilder sb = new StringBuilder(message).append("\n\n");
            // 只贴战斗日志尾部，避免一次挑战刷屏
            int from = Math.max(0, logs.size() - 8);
            for (int i = from; i < logs.size(); i++) {
                sb.append(logs.get(i)).append("\n");
            }
            ctx.reply(sb.toString());
        } else {
            ctx.reply(message);
        }
    }

    private void handleRank(CommandContext ctx, PlayerInfo p, String[] parts) {
        String query = parts.length > 1 ? parts[1].trim() : "1";
        List<WorldBoss> pool = worldBossService.getPool(WorldBossService.scope(), System.currentTimeMillis());
        WorldBoss boss = pickByIndex(pool, query);
        if (boss == null) {
            ctx.reply(I18n.t("worldboss.h535cf9d3"));
            return;
        }
        ctx.reply(buildLeaderboard(boss));
    }

    private void handleRefresh(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (!ctx.requirePermission(ADMIN_PERMISSION)) {
            return;
        }
        List<WorldBoss> pool = worldBossService.refreshPool(WorldBossService.scope(), System.currentTimeMillis());
        ctx.reply(I18n.t("worldboss.h49971702", pool.size(), describePool(pool, null)));
    }

    // ==================== 文案 ====================

    private String buildPoolOverview(PlayerInfo p) {
        long now = System.currentTimeMillis();
        List<WorldBoss> pool = worldBossService.getPool(WorldBossService.scope(), now);
        StringBuilder sb = new StringBuilder();
        sb.append("════ 世界Boss ════\n");
        sb.append("血量为全服共享，奖励按伤害比例分红\n\n");
        sb.append(describePool(pool, p));
        sb.append("\n/世界boss 挑战 <序号>    出手\n");
        sb.append("/世界boss 排行 <序号>    查看贡献榜");
        return sb.toString();
    }

    private String describePool(List<WorldBoss> pool, PlayerInfo viewer) {
        if (pool.isEmpty()) {
            return "当前没有世界Boss。";
        }
        long now = System.currentTimeMillis();
        long cooldownMs = WorldBossService.challengeCooldownMs();
        StringBuilder sb = new StringBuilder();
        for (WorldBoss boss : pool) {
            sb.append(boss.getSlot()).append(". ").append(boss.getName());
            sb.append("（").append(CommandContext.realmName(boss.getRealm())).append("）\n");
            if (boss.isDead()) {
                sb.append("   已被击杀");
                if (boss.getKillerName() != null) {
                    sb.append("　最后一刀：").append(boss.getKillerName());
                }
                sb.append("\n");
            } else {
                sb.append("   气血 ").append(boss.getHp()).append("/").append(boss.getMaxHp());
                sb.append("（").append(boss.hpPercent()).append("%）");
                sb.append(bar(boss.hpPercent())).append("\n");
                sb.append("   灵石预算 ").append(boss.getStoneBudget())
                        .append("　修为预算 ").append(boss.getExpBudget()).append("\n");
            }
            sb.append("   整池重建：").append(formatTime(boss.getExpiresAt())).append("\n");
            if (viewer != null && !boss.isDead()) {
                WorldBossDamage mine = worldBossService.getDamage(boss.getId(), viewer.getId());
                if (mine != null) {
                    sb.append("   我的累计伤害：").append(mine.getDamage())
                            .append("（").append(mine.getHits()).append(" 次）");
                    long remain = mine.remainingCooldownMs(now, cooldownMs);
                    if (remain > 0) {
                        sb.append("　冷却 ").append(remain / 1000).append(" 秒");
                    }
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildLeaderboard(WorldBoss boss) {
        List<WorldBossDamage> board = worldBossService.getLeaderboard(boss.getId(), 10);
        StringBuilder sb = new StringBuilder();
        sb.append("════ ").append(boss.getName()).append(" 伤害榜 ════\n");
        sb.append("气血 ").append(Math.max(0, boss.getHp())).append("/").append(boss.getMaxHp()).append("\n\n");
        if (board.isEmpty()) {
            sb.append("还没有人出手。");
            return sb.toString();
        }
        int rank = 1;
        for (WorldBossDamage d : board) {
            long percent = boss.getMaxHp() > 0 ? d.getDamage() * 100 / boss.getMaxHp() : 0;
            sb.append(rank++).append(". ")
                    .append(d.getPlayerName() != null ? d.getPlayerName() : ("UID " + d.getPlayerId()))
                    .append("　伤害 ").append(d.getDamage())
                    .append("（").append(percent).append("%）")
                    .append("　").append(d.getHits()).append(" 次\n");
        }
        return sb.toString();
    }

    private static String bar(int percent) {
        int filled = Math.max(0, Math.min(10, percent / 10));
        StringBuilder sb = new StringBuilder("   [");
        for (int i = 0; i < 10; i++) {
            sb.append(i < filled ? '█' : '░');
        }
        return sb.append(']').toString();
    }

    private static WorldBoss pickByIndex(List<WorldBoss> pool, String query) {
        if (pool.isEmpty()) {
            return null;
        }
        try {
            int index = Integer.parseInt(query.trim());
            if (index >= 1 && index <= pool.size()) {
                return pool.get(index - 1);
            }
            for (WorldBoss boss : pool) {
                if (boss.getId() == index) {
                    return boss;
                }
            }
        } catch (NumberFormatException ignored) {
            for (WorldBoss boss : pool) {
                if (boss.getName().equals(query.trim())) {
                    return boss;
                }
            }
        }
        return null;
    }

    private static String formatTime(long epochMillis) {
        return java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(epochMillis));
    }

    // ==================== HTTP ====================

    private JsonObject handleListHttp(RouteDefinition.RestContext ctx) {
        long now = System.currentTimeMillis();
        List<WorldBoss> pool = worldBossService.getPool(WorldBossService.scope(), now);
        JsonObject data = new JsonObject();
        JsonArray arr = new JsonArray();
        for (WorldBoss boss : pool) {
            arr.add(toJson(boss, ctx.playerId()));
        }
        data.add("bosses", arr);
        data.addProperty("scope", WorldBossService.scope());
        data.addProperty("serverTime", now);
        data.addProperty("cooldownSeconds", WorldBossService.challengeCooldownMs() / 1000);
        return GameMessage.restOk(I18n.t("common.rest.ok"), data);
    }

    private JsonObject handleChallengeHttp(RouteDefinition.RestContext ctx) {
        String query = ctx.queryParam("boss");
        if (query == null || query.isBlank()) {
            try {
                JsonObject body = ctx.bodyJson();
                if (body.has("boss")) {
                    query = body.get("boss").getAsString();
                }
            } catch (RuntimeException ignored) {
                // 无 body 或 body 不是 JSON：留空，走默认第一只
            }
        }
        Map<String, Object> result = worldBossService.challenge(
                ctx.playerId(), WorldBossService.scope(), query, System.currentTimeMillis());
        JsonObject data = new JsonObject();
        data.addProperty("bossName", str(result.get("bossName")));
        data.addProperty("damage", num(result.get("damage")));
        data.addProperty("hpRemaining", num(result.get("hpRemaining")));
        data.addProperty("maxHp", num(result.get("maxHp")));
        data.addProperty("killed", Boolean.TRUE.equals(result.get("killed")));
        data.addProperty("stones", num(result.get("stones")));
        data.addProperty("exp", num(result.get("exp")));
        data.addProperty("capped", Boolean.TRUE.equals(result.get("capped")));
        data.addProperty("playerWon", Boolean.TRUE.equals(result.get("playerWon")));
        if (Boolean.TRUE.equals(result.get("success"))) {
            return GameMessage.restOk((String) result.get("message"), data);
        }
        return GameMessage.restError(400, (String) result.get("message"));
    }

    private JsonObject handleAdminListHttp(RouteDefinition.RestContext ctx) {
        long now = System.currentTimeMillis();
        List<WorldBoss> pool = worldBossService.getPool(WorldBossService.scope(), now);
        JsonObject data = new JsonObject();
        JsonArray arr = new JsonArray();
        for (WorldBoss boss : pool) {
            JsonObject o = toJson(boss, 0);
            JsonArray board = new JsonArray();
            for (WorldBossDamage d : worldBossService.getLeaderboard(boss.getId(), 10)) {
                JsonObject row = new JsonObject();
                row.addProperty("playerId", d.getPlayerId());
                row.addProperty("playerName", d.getPlayerName());
                row.addProperty("damage", d.getDamage());
                row.addProperty("hits", d.getHits());
                board.add(row);
            }
            o.add("leaderboard", board);
            arr.add(o);
        }
        data.add("bosses", arr);
        data.addProperty("scope", WorldBossService.scope());
        data.addProperty("serverTime", now);
        return GameMessage.restOk(I18n.t("common.rest.ok"), data);
    }

    private JsonObject handleAdminRefreshHttp(RouteDefinition.RestContext ctx) {
        List<WorldBoss> pool = worldBossService.refreshPool(WorldBossService.scope(), System.currentTimeMillis());
        JsonObject data = new JsonObject();
        JsonArray arr = new JsonArray();
        for (WorldBoss boss : pool) {
            arr.add(toJson(boss, 0));
        }
        data.add("bosses", arr);
        return GameMessage.restOk(I18n.t("worldboss.pool_rebuilt"), data);
    }

    private JsonObject toJson(WorldBoss boss, long viewerPlayerId) {
        JsonObject o = new JsonObject();
        o.addProperty("id", boss.getId());
        o.addProperty("slot", boss.getSlot());
        o.addProperty("name", boss.getName());
        o.addProperty("description", boss.getDescription());
        o.addProperty("realm", boss.getRealm());
        o.addProperty("hp", boss.getHp());
        o.addProperty("maxHp", boss.getMaxHp());
        o.addProperty("hpPercent", boss.hpPercent());
        o.addProperty("stoneBudget", boss.getStoneBudget());
        o.addProperty("expBudget", boss.getExpBudget());
        o.addProperty("expiresAt", boss.getExpiresAt());
        o.addProperty("dead", boss.isDead());
        o.addProperty("killedAt", boss.getKilledAt());
        o.addProperty("killerPlayerId", boss.getKillerPlayerId());
        o.addProperty("killerName", boss.getKillerName());
        if (viewerPlayerId > 0) {
            WorldBossDamage mine = worldBossService.getDamage(boss.getId(), viewerPlayerId);
            o.addProperty("myDamage", mine != null ? mine.getDamage() : 0);
            o.addProperty("myHits", mine != null ? mine.getHits() : 0);
            o.addProperty("myCooldownMs", mine != null
                    ? mine.remainingCooldownMs(System.currentTimeMillis(), WorldBossService.challengeCooldownMs())
                    : 0);
        }
        return o;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
