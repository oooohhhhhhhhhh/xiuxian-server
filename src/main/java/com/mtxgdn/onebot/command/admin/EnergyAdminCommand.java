package com.mtxgdn.onebot.command.admin;

import com.google.gson.JsonObject;
import com.mtxgdn.plugin.BuiltInPluginRegistry;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.service.EnergyService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;
import com.mtxgdn.i18n.I18n;

public class EnergyAdminCommand extends Command {

    /** 实验性插件名：能量转化/等价交换（开关见 config/plugins.yml 的 plugins.experimental_energy_exchange）。 */
    private static final String ENERGY_EXCHANGE_PLUGIN = "experimental_energy_exchange";

    public EnergyAdminCommand() {
        super(new String[]{"能量管理", "energyadmin", "energymanage"},
                "管理玩家的能量值（仅私聊）",
                "/能量管理 <玩家ID> <设置|增加|减少|查看> [数值]",
                "管理", "admin.status", true);

        // REST API 路由（管理员专用）
        // 读（查看能量）沿用 admin.status；写（设置/增加/减少）必须 admin.users.manage。
        // 分开是因为 MODERATOR 组默认持有 admin.status —— 若写路由也用它门控，版主即可任意增删能量。
        addRoute(RouteDefinition.get("admin/energy/{playerId}", "admin.status", this::handleGetEnergyHttp));
        addRoute(RouteDefinition.post("admin/energy/set", "admin.users.manage", this::handleSetEnergyHttp));
        addRoute(RouteDefinition.post("admin/energy/add", "admin.users.manage", this::handleAddEnergyHttp));
        addRoute(RouteDefinition.post("admin/energy/remove", "admin.users.manage", this::handleRemoveEnergyHttp));
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = ctx.resolveBoundBinding();
        if (b == null) {
            ctx.reply("请先绑定账号。");
            return;
        }
        if (!PermissionService.hasPermission(b.getUserId(), "admin.status")) {
            ctx.reply("权限不足，你无权使用此功能。");
            return;
        }
        if (!BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE_PLUGIN)) {
            ctx.reply("能量转化系统暂未开放，请在 config/plugins.yml 中将 experimental_energy_exchange 设为 true（或在 WebUI「插件管理」页启用该实验性插件）。");
            return;
        }

        String arg = ctx.getArg();
        if (arg == null || arg.isBlank()) {
            ctx.reply("用法: /能量管理 <玩家ID> <设置|增加|减少|查看> [数值]\n" +
                    "示例:\n" +
                    "  /能量管理 1 查看 - 查看玩家能量\n" +
                    "  /能量管理 1 设置 1000 - 设置为1000\n" +
                    "  /能量管理 1 增加 500 - 增加500\n" +
                    "  /能量管理 1 减少 300 - 减少300");
            return;
        }

        String[] parts = arg.trim().split("\\s+");
        if (parts.length < 2) {
            ctx.reply("参数不足。用法: /能量管理 <玩家ID> <设置|增加|减少|查看> [数值]");
            return;
        }

        long playerId;
        try {
            playerId = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            ctx.reply("玩家ID必须为数字。");
            return;
        }

        PlayerService playerService = ServiceRegistry.getPlayerService();
        var p = playerService.getPlayerById(playerId);
        if (p == null) {
            ctx.reply("未找到玩家 ID:" + playerId);
            return;
        }

        EnergyService energyService = ServiceRegistry.getEnergyService();
        String action = parts[1];

        // 逐操作校验：查看沿用指令级 admin.status；设置/增加/减少是写操作，必须 admin.users.manage。
        // 指令级权限只能是二者之一，若整条指令都用 admin.status 门控，
        // MODERATOR 组（默认持有 admin.status）就能任意增删玩家能量。
        if (isWriteAction(action)
                && !PermissionService.hasPermission(b.getUserId(), "admin.users.manage")) {
            ctx.reply("权限不足：修改玩家能量需要 admin.users.manage 权限（仅超级管理员）。");
            return;
        }

        switch (action) {
            case "查看":
            case "view":
                long energy = energyService.getEnergy(playerId);
                ctx.reply("玩家 " + p.getName() + " (ID:" + playerId + ") 当前能量: " + energy);
                break;

            case "设置":
            case "set":
                if (parts.length < 3) {
                    ctx.reply("请提供要设置的能量值。用法: /能量管理 " + playerId + " 设置 <数值>");
                    return;
                }
                long setValue;
                try {
                    setValue = Long.parseLong(parts[2]);
                } catch (NumberFormatException e) {
                    ctx.reply("数值必须为整数。");
                    return;
                }
                if (setValue < 0) {
                    ctx.reply("能量值不能为负数。");
                    return;
                }
                energyService.setEnergy(playerId, setValue);
                ctx.reply("已将玩家 " + p.getName() + " (ID:" + playerId + ") 的能量设置为 " + setValue);
                break;

            case "增加":
            case "add":
                if (parts.length < 3) {
                    ctx.reply("请提供要增加的能量值。用法: /能量管理 " + playerId + " 增加 <数值>");
                    return;
                }
                long addValue;
                try {
                    addValue = Long.parseLong(parts[2]);
                } catch (NumberFormatException e) {
                    ctx.reply("数值必须为整数。");
                    return;
                }
                if (addValue <= 0) {
                    ctx.reply("增加量必须大于0。");
                    return;
                }
                energyService.addEnergy(playerId, addValue);
                ctx.reply("已为玩家 " + p.getName() + " (ID:" + playerId + ") 增加 " + addValue + " 能量\n当前能量: " + energyService.getEnergy(playerId));
                break;

            case "减少":
            case "remove":
                if (parts.length < 3) {
                    ctx.reply("请提供要减少的能量值。用法: /能量管理 " + playerId + " 减少 <数值>");
                    return;
                }
                long removeValue;
                try {
                    removeValue = Long.parseLong(parts[2]);
                } catch (NumberFormatException e) {
                    ctx.reply("数值必须为整数。");
                    return;
                }
                if (removeValue <= 0) {
                    ctx.reply("减少量必须大于0。");
                    return;
                }
                long current = energyService.getEnergy(playerId);
                if (current < removeValue) {
                    ctx.reply("能量不足，无法减少。玩家当前能量: " + current + "，尝试减少: " + removeValue);
                    return;
                }
                energyService.removeEnergy(playerId, removeValue);
                ctx.reply("已从玩家 " + p.getName() + " (ID:" + playerId + ") 减少 " + removeValue + " 能量\n当前能量: " + energyService.getEnergy(playerId));
                break;

            default:
                ctx.reply("未知操作: " + action + "\n可用操作: 查看, 设置, 增加, 减少");
                break;
        }
    }

    /** 该操作是否会改动玩家能量（写操作需要 admin.users.manage）。 */
    private static boolean isWriteAction(String action) {
        return switch (action) {
            case "设置", "set", "增加", "add", "减少", "remove" -> true;
            default -> false;
        };
    }

    // ==================== REST API 处理器 ====================

    private JsonObject checkFeatureEnabled() {
        JsonObject result = new JsonObject();
        if (!BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE_PLUGIN)) {
            result.addProperty("code", 403);
            result.addProperty("message", I18n.t("admin.h4aa0a0f2"));
        }
        return result;
    }

    /**
     * GET /game/admin/energy/{playerId}
     * 查看指定玩家的能量值
     */
    private JsonObject handleGetEnergyHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = checkFeatureEnabled();
        if (result.has("code")) return result;

        try {
            long playerId = ctx.pathParamLong("playerId");
            PlayerService playerService = ServiceRegistry.getPlayerService();
            var p = playerService.getPlayerById(playerId);
            if (p == null) {
                result.addProperty("code", 404);
                result.addProperty("message", "玩家不存在: " + playerId);
                return result;
            }

            EnergyService energyService = ServiceRegistry.getEnergyService();
            long energy = energyService.getEnergy(playerId);

            result.addProperty("code", 200);
            result.addProperty("playerId", playerId);
            result.addProperty("playerName", p.getName());
            result.addProperty("energy", energy);
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }

    /**
     * POST /game/admin/energy/set
     * Body: { "playerId": 1, "energy": 1000 }
     */
    private JsonObject handleSetEnergyHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = checkFeatureEnabled();
        if (result.has("code")) return result;

        try {
            JsonObject req = ctx.bodyJson();
            long playerId = req.get("playerId").getAsLong();
            long energy = req.get("energy").getAsLong();

            if (energy < 0) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("admin.h35fbfdd4"));
                return result;
            }

            PlayerService playerService = ServiceRegistry.getPlayerService();
            var p = playerService.getPlayerById(playerId);
            if (p == null) {
                result.addProperty("code", 404);
                result.addProperty("message", "玩家不存在: " + playerId);
                return result;
            }

            EnergyService energyService = ServiceRegistry.getEnergyService();
            energyService.setEnergy(playerId, energy);

            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h34ac6234"));
            result.addProperty("playerId", playerId);
            result.addProperty("playerName", p.getName());
            result.addProperty("energy", energy);
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }

    /**
     * POST /game/admin/energy/add
     * Body: { "playerId": 1, "energy": 500 }
     */
    private JsonObject handleAddEnergyHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = checkFeatureEnabled();
        if (result.has("code")) return result;

        try {
            JsonObject req = ctx.bodyJson();
            long playerId = req.get("playerId").getAsLong();
            long amount = req.get("energy").getAsLong();

            if (amount <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("admin.hab204d78"));
                return result;
            }

            PlayerService playerService = ServiceRegistry.getPlayerService();
            var p = playerService.getPlayerById(playerId);
            if (p == null) {
                result.addProperty("code", 404);
                result.addProperty("message", "玩家不存在: " + playerId);
                return result;
            }

            EnergyService energyService = ServiceRegistry.getEnergyService();
            energyService.addEnergy(playerId, amount);

            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h308c401c"));
            result.addProperty("playerId", playerId);
            result.addProperty("playerName", p.getName());
            result.addProperty("added", amount);
            result.addProperty("currentEnergy", energyService.getEnergy(playerId));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }

    /**
     * POST /game/admin/energy/remove
     * Body: { "playerId": 1, "energy": 300 }
     */
    private JsonObject handleRemoveEnergyHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = checkFeatureEnabled();
        if (result.has("code")) return result;

        try {
            JsonObject req = ctx.bodyJson();
            long playerId = req.get("playerId").getAsLong();
            long amount = req.get("energy").getAsLong();

            if (amount <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("admin.h53593588"));
                return result;
            }

            PlayerService playerService = ServiceRegistry.getPlayerService();
            var p = playerService.getPlayerById(playerId);
            if (p == null) {
                result.addProperty("code", 404);
                result.addProperty("message", "玩家不存在: " + playerId);
                return result;
            }

            EnergyService energyService = ServiceRegistry.getEnergyService();
            long current = energyService.getEnergy(playerId);
            if (current < amount) {
                result.addProperty("code", 400);
                result.addProperty("message", "能量不足，当前 " + current + "，尝试减少 " + amount);
                return result;
            }

            energyService.removeEnergy(playerId, amount);

            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h6e950058"));
            result.addProperty("playerId", playerId);
            result.addProperty("playerName", p.getName());
            result.addProperty("removed", amount);
            result.addProperty("currentEnergy", energyService.getEnergy(playerId));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }
}
