package com.mtxgdn.onebot.command.economy;

import com.mtxgdn.game.economy.DepositView;
import com.mtxgdn.game.economy.BankWithdrawResult;
import com.mtxgdn.game.economy.BankInfo;
import com.mtxgdn.game.economy.BankDepositResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.List;
import java.util.Map;
import com.mtxgdn.i18n.I18n;

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
        
        setI18nKeys("cmd.bank.desc", "cmd.bank.usage");
    setModule("economy");

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
        BankInfo info = eco.getBankInfo(p.getId());
        long stones = ServiceRegistry.getItemService().getSpiritStoneCount(p.getId());

        List<DepositView> deposits = info.deposits();
        long totalPrincipal = info.totalPrincipal();
        long totalInterest = info.totalPendingInterest();

        StringBuilder sb = new StringBuilder();
        sb.append("🏦 灵庄\n");
        sb.append("随身灵石: ").append(stones).append("\n");

        if (deposits.isEmpty()) {
            sb.append("\n暂无存款。\n\n");
        } else {
            sb.append("存于灵庄: ").append(totalPrincipal).append("（预估利息 +").append(totalInterest).append("）\n");
            sb.append("────────\n");
            for (DepositView dep : deposits) {
                sb.append("#").append(dep.id()).append(" ")
                  .append(dep.type()).append(" | 本金 ").append(dep.principal()).append(" | ");
                if (dep.estimatedInterest() > 0) sb.append("预估利息 +").append(dep.estimatedInterest());
                sb.append("\n  ").append(dep.note()).append("\n");
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
            ctx.reply(I18n.t("economy.usage_6"));
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
            ctx.reply(I18n.t("economy.deposit_types"));
            return;
        }

        long amount;
        try { amount = Long.parseLong(parts[2]); } catch (NumberFormatException e) {
            ctx.reply(I18n.t("economy.invalid_quantity")); return;
        }

        var eco = ServiceRegistry.getEconomyService();
        BankDepositResult result = eco.bankDeposit(p.getId(), type, amount);
        ctx.reply(result.message());
    }

    private void doWithdraw(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (parts.length < 2) {
            ctx.reply(I18n.t("economy.usage"));
            return;
        }

        long depositId;
        try { depositId = Long.parseLong(parts[1]); } catch (NumberFormatException e) {
            ctx.reply(I18n.t("economy.invalid_id")); return;
        }

        var eco = ServiceRegistry.getEconomyService();
        BankWithdrawResult result = eco.bankWithdraw(p.getId(), depositId);
        ctx.reply(result.message());
    }

    private void showRates(CommandContext ctx, PlayerInfo p, String[] parts) {
        ctx.reply("""
            🏦 灵庄利率表
            ────────
            活期: 日利 0.2%（复利，随时可取）
            ────────
            7天定期: 到期利息 4%
              例: 存 1000→到期 1040
            30天定期: 到期利息 15%
              例: 存 1000→到期 1150
            90天定期: 到期利息 40%
              例: 存 1000→到期 1400
            ────────
            ⚠ 定期提前取出损失全部利息！
            起存 100 灵石""");
    }

    // ==================== REST API ====================

    private JsonObject handleBankInfoHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            var eco = ServiceRegistry.getEconomyService();
            BankInfo info = eco.getBankInfo(ctx.playerId());
            long stones = ServiceRegistry.getItemService().getSpiritStoneCount(ctx.playerId());

            JsonArray arr = new JsonArray();
            for (DepositView dep : info.deposits()) {
                JsonObject io = new JsonObject();
                io.addProperty("id", dep.id());
                io.addProperty("type", dep.type());
                io.addProperty("principal", dep.principal());
                io.addProperty("estimatedInterest", dep.estimatedInterest());
                io.addProperty("note", dep.note());
                arr.add(io);
            }
            result.addProperty("code", 200);
            result.addProperty("spiritStones", stones);
            result.addProperty("totalPrincipal", info.totalPrincipal());
            result.addProperty("totalPendingInterest", info.totalPendingInterest());
            result.add("deposits", arr);
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("economy.server_error", e.getMessage()));
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
                result.addProperty("message", I18n.t("economy.please_provide_a_deposit_type"));
                return result;
            }
            var eco = ServiceRegistry.getEconomyService();
            BankDepositResult data = eco.bankDeposit(ctx.playerId(), type, amount);
            result.addProperty("code", data.success() ? 200 : 400);
            result.addProperty("success", data.success());
            result.addProperty("message", data.message());
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("economy.server_error", e.getMessage()));
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
                result.addProperty("message", I18n.t("economy.please_provide_a_valid_deposit"));
                return result;
            }
            var eco = ServiceRegistry.getEconomyService();
            BankWithdrawResult data = eco.bankWithdraw(ctx.playerId(), depositId);
            result.addProperty("code", data.success() ? 200 : 400);
            result.addProperty("success", data.success());
            result.addProperty("message", data.message());
            if (data.interest() != null) result.addProperty("interest", data.interest());
            if (data.principal() != null) result.addProperty("principal", data.principal());
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("economy.server_error", e.getMessage()));
        }
        return result;
    }
}
