package com.mtxgdn.onebot.command.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.List;
import java.util.Map;

/**
 * 灵庄系统 — 灵石存取、定期/活期、利息结算。
 *
 * /灵庄              — 查看账户
 * /灵庄 存入 <活期|7天|30天|90天> <数量>  — 存款
 * /灵庄 取出 <编号>   — 取款
 * /灵庄 利率         — 查看利率表
 */
public class BankCommand extends Command {
    public BankCommand() {
        super(new String[]{"灵庄", "bank"}, "灵石存取·利息生息", "/灵庄 [存入|取出|利率]", "经济", null);

        registerSub("存入", (ctx, p, parts) -> doDeposit(ctx, p, parts));
        registerSub("取出", (ctx, p, parts) -> doWithdraw(ctx, p, parts));
        registerSub("利率", (ctx, p, parts) -> showRates(ctx, p, parts));

        addRoute(RouteDefinition.get("economy/bank/info", this::handleBankInfoHttp));
        addRoute(RouteDefinition.post("economy/bank/deposit", this::handleDepositHttp));
        addRoute(RouteDefinition.post("economy/bank/withdraw", this::handleWithdrawHttp));
    }

    @Override
    public void execute(CommandContext ctx) {
        // 有参数时走基类的子命令分发（存入/取出/利率）
        String arg = ctx.getArg();
        if (arg != null && !arg.trim().isEmpty()) {
            super.execute(ctx);
            return;
        }

        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        showAccount(ctx, p);
    }

    private void showAccount(CommandContext ctx, PlayerInfo p) {
        var eco = ServiceRegistry.getEconomyService();
        var info = eco.getBankInfo(p.getId());
        long stones = ServiceRegistry.getItemService().getSpiritStoneCount(p.getId());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deposits = (List<Map<String, Object>>) info.get("deposits");
        long totalPrincipal = (long) info.get("totalPrincipal");
        long totalInterest = (long) info.get("totalPendingInterest");

        StringBuilder sb = new StringBuilder();
        sb.append("🏦 灵庄\n");
        sb.append("随身灵石: ").append(stones).append("\n");

        if (deposits == null || deposits.isEmpty()) {
            sb.append("\n暂无存款。\n\n");
        } else {
            sb.append("存于灵庄: ").append(totalPrincipal).append("（预估利息 +").append(totalInterest).append("）\n");
            sb.append("────────\n");
            for (var dep : deposits) {
                long id = (long) dep.get("id");
                sb.append("#").append(id).append(" ")
                  .append(dep.get("type")).append(" | 本金 ").append(dep.get("principal")).append(" | ");
                long interest = (long) dep.get("estimatedInterest");
                if (interest > 0) sb.append("预估利息 +").append(interest);
                sb.append("\n  ").append(dep.get("note")).append("\n");
            }
        }

        sb.append("────────\n");
        sb.append("存入: /灵庄 存入 <活期|7天|30天|90天> <数量>\n");
        sb.append("取款: /灵庄 取出 <编号>\n");
        sb.append("利率: /灵庄 利率");

        ctx.reply(sb.toString());
    }

    private void doDeposit(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (parts.length < 3) {
            ctx.reply("用法: /灵庄 存入 <活期|7天|30天|90天> <数量>\n例: /灵庄 存入 30天 1000");
            return;
        }

        String type = switch (parts[1]) {
            case "活期" -> "current";
            case "7天" -> "fixed_7";
            case "30天" -> "fixed_30";
            case "90天" -> "fixed_90";
            default -> null;
        };
        if (type == null) {
            ctx.reply("存款类型: 活期 / 7天 / 30天 / 90天\n例: /灵庄 存入 30天 1000");
            return;
        }

        long amount;
        try { amount = Long.parseLong(parts[2]); } catch (NumberFormatException e) {
            ctx.reply("数量无效"); return;
        }

        var eco = ServiceRegistry.getEconomyService();
        var result = eco.bankDeposit(p.getId(), type, amount);
        ctx.reply((String) result.get("message"));
    }

    private void doWithdraw(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (parts.length < 2) {
            ctx.reply("用法: /灵庄 取出 <编号>\n先用 /灵庄 查看存款编号");
            return;
        }

        long depositId;
        try { depositId = Long.parseLong(parts[1]); } catch (NumberFormatException e) {
            ctx.reply("编号无效"); return;
        }

        var eco = ServiceRegistry.getEconomyService();
        var result = eco.bankWithdraw(p.getId(), depositId);
        ctx.reply((String) result.get("message"));
    }

    private void showRates(CommandContext ctx, PlayerInfo p, String[] parts) {
        ctx.reply("""
            🏦 灵庄利率表
            ────────
            活期: 日利 0.5%（复利，随时可取）
            ────────
            7天定期: 到期利息 3%
              例: 存 1000→到期 1030
            30天定期: 到期利息 10%
              例: 存 1000→到期 1100
            90天定期: 到期利息 25%
              例: 存 1000→到期 1250
            ────────
            ⚠ 定期提前取出损失全部利息！
            起存 100 灵石""");
    }

    // ==================== REST API ====================

    private JsonObject handleBankInfoHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            var eco = ServiceRegistry.getEconomyService();
            var info = eco.getBankInfo(ctx.playerId());
            long stones = ServiceRegistry.getItemService().getSpiritStoneCount(ctx.playerId());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> deposits = (List<Map<String, Object>>) info.get("deposits");
            JsonArray arr = new JsonArray();
            if (deposits != null) {
                for (var dep : deposits) {
                    JsonObject io = new JsonObject();
                    io.addProperty("id", (long) dep.get("id"));
                    io.addProperty("type", (String) dep.get("type"));
                    io.addProperty("principal", (long) dep.get("principal"));
                    io.addProperty("estimatedInterest", (long) dep.get("estimatedInterest"));
                    io.addProperty("note", (String) dep.get("note"));
                    arr.add(io);
                }
            }
            result.addProperty("code", 200);
            result.addProperty("spiritStones", stones);
            result.addProperty("totalPrincipal", (long) info.get("totalPrincipal"));
            result.addProperty("totalPendingInterest", (long) info.get("totalPendingInterest"));
            result.add("deposits", arr);
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }

    private JsonObject handleDepositHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            JsonObject req = ctx.bodyJson();
            String type = req.has("type") ? req.get("type").getAsString() : "";
            long amount = req.has("amount") ? req.get("amount").getAsLong() : 0;
            if (type.isBlank() || amount <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", "请提供存款类型(type: current/fixed_7/fixed_30/fixed_90)和金额(amount)");
                return result;
            }
            var eco = ServiceRegistry.getEconomyService();
            var data = eco.bankDeposit(ctx.playerId(), type, amount);
            result.addProperty("code", (boolean) data.get("success") ? 200 : 400);
            result.addProperty("success", (boolean) data.get("success"));
            result.addProperty("message", (String) data.get("message"));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }

    private JsonObject handleWithdrawHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            JsonObject req = ctx.bodyJson();
            long depositId = req.has("depositId") ? req.get("depositId").getAsLong() : 0;
            if (depositId <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", "请提供有效的存款编号(depositId)");
                return result;
            }
            var eco = ServiceRegistry.getEconomyService();
            var data = eco.bankWithdraw(ctx.playerId(), depositId);
            result.addProperty("code", (boolean) data.get("success") ? 200 : 400);
            result.addProperty("success", (boolean) data.get("success"));
            result.addProperty("message", (String) data.get("message"));
            if (data.containsKey("interest")) result.addProperty("interest", (long) data.get("interest"));
            if (data.containsKey("principal")) result.addProperty("principal", (long) data.get("principal"));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }
}
