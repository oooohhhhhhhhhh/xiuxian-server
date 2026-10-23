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

        
        setI18nKeys("cmd.energy_admin.desc", "cmd.energy_admin.usage");
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
            ctx.reply(I18n.t("admin.please_bind_your_account_first_2"));
            return;
        }
        if (!PermissionService.hasPermission(b.getUserId(), "admin.status")) {
            ctx.reply(I18n.t("admin.insufficient_permission_3"));
            return;
        }
        if (!BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE_PLUGIN)) {
            ctx.reply(I18n.t("admin.the_energy_conversion_system_2"));
            return;
        }

        String arg = ctx.getArg();
        if (arg == null || arg.isBlank()) {
            ctx.reply(I18n.t("admin.energy_admin.usage"));
            return;
        }

        String[] parts = arg.trim().split("\\s+");
        if (parts.length < 2) {
            ctx.reply(I18n.t("admin.missing_arguments"));
            return;
        }

        long playerId;
        try {
            playerId = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            ctx.reply(I18n.t("admin.player_id_must"));
            return;
        }

        PlayerService playerService = ServiceRegistry.getPlayerService();
        var p = playerService.getPlayerById(playerId);
        if (p == null) {
            ctx.reply(I18n.t("admin.player_not_found_id", playerId));
            return;
        }

        EnergyService energyService = ServiceRegistry.getEnergyService();
        String action = parts[1];

        // 逐操作校验：查看沿用指令级 admin.status；设置/增加/减少是写操作，必须 admin.users.manage。
        // 指令级权限只能是二者之一，若整条指令都用 admin.status 门控，
        // MODERATOR 组（默认持有 admin.status）就能任意增删玩家能量。
        if (isWriteAction(action)
                && !PermissionService.hasPermission(b.getUserId(), "admin.users.manage")) {
            ctx.reply(I18n.t("admin.insufficient_permission_2"));
            return;
        }

        switch (action) {
            case "查看":
            case "view":
                long energy = energyService.getEnergy(playerId);
                ctx.reply(I18n.t("admin.player_current_energy", p.getName(), playerId, energy));
                break;

            case "设置":
            case "set":
                if (parts.length < 3) {
                    ctx.reply(I18n.t("admin.please_provide_the_energy_amount_2", playerId));
                    return;
                }
                long setValue;
                try {
                    setValue = Long.parseLong(parts[2]);
                } catch (NumberFormatException e) {
                    ctx.reply(I18n.t("admin.the_value_must"));
                    return;
                }
                if (setValue < 0) {
                    ctx.reply(I18n.t("admin.energy_cannot_be_negative_2"));
                    return;
                }
                energyService.setEnergy(playerId, setValue);
                ctx.reply(I18n.t("admin.set_the_energy_of_player", p.getName(), playerId, setValue));
                break;

            case "增加":
            case "add":
                if (parts.length < 3) {
                    ctx.reply(I18n.t("admin.please_provide_the_energy_amount_3", playerId));
                    return;
                }
                long addValue;
                try {
                    addValue = Long.parseLong(parts[2]);
                } catch (NumberFormatException e) {
                    ctx.reply(I18n.t("admin.the_value_must"));
                    return;
                }
                if (addValue <= 0) {
                    ctx.reply(I18n.t("admin.the_increase_amount_must_2"));
                    return;
                }
                energyService.addEnergy(playerId, addValue);
                ctx.reply(I18n.t("admin.added_energy_to_player_ncurrent", p.getName(), playerId, addValue, energyService.getEnergy(playerId)));
                break;

            case "减少":
            case "remove":
                if (parts.length < 3) {
                    ctx.reply(I18n.t("admin.please_provide_the_energy_amount", playerId));
                    return;
                }
                long removeValue;
                try {
                    removeValue = Long.parseLong(parts[2]);
                } catch (NumberFormatException e) {
                    ctx.reply(I18n.t("admin.the_value_must"));
                    return;
                }
                if (removeValue <= 0) {
                    ctx.reply(I18n.t("admin.the_reduction_amount_must_2"));
                    return;
                }
                long current = energyService.getEnergy(playerId);
                if (current < removeValue) {
                    ctx.reply(I18n.t("admin.not_enough_energy_to_reduce", current, removeValue));
                    return;
                }
                energyService.removeEnergy(playerId, removeValue);
                ctx.reply(I18n.t("admin.reduced_energy_from_player_ncurrent", p.getName(), playerId, removeValue, energyService.getEnergy(playerId)));
                break;

            default:
                ctx.reply(I18n.t("admin.unknown_operation", action));
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
            result.addProperty("message", I18n.t("admin.the_energy_conversion_system"));
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
                result.addProperty("message", I18n.t("admin.player_not_found_2", playerId));
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
            result.addProperty("message", I18n.t("admin.server_error", e.getMessage()));
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
                result.addProperty("message", I18n.t("admin.energy_cannot_be_negative"));
                return result;
            }

            PlayerService playerService = ServiceRegistry.getPlayerService();
            var p = playerService.getPlayerById(playerId);
            if (p == null) {
                result.addProperty("code", 404);
                result.addProperty("message", I18n.t("admin.player_not_found_2", playerId));
                return result;
            }

            EnergyService energyService = ServiceRegistry.getEnergyService();
            energyService.setEnergy(playerId, energy);

            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.set_successfully"));
            result.addProperty("playerId", playerId);
            result.addProperty("playerName", p.getName());
            result.addProperty("energy", energy);
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("admin.server_error", e.getMessage()));
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
                result.addProperty("message", I18n.t("admin.the_increase_amount_must"));
                return result;
            }

            PlayerService playerService = ServiceRegistry.getPlayerService();
            var p = playerService.getPlayerById(playerId);
            if (p == null) {
                result.addProperty("code", 404);
                result.addProperty("message", I18n.t("admin.player_not_found_2", playerId));
                return result;
            }

            EnergyService energyService = ServiceRegistry.getEnergyService();
            energyService.addEnergy(playerId, amount);

            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.added_successfully"));
            result.addProperty("playerId", playerId);
            result.addProperty("playerName", p.getName());
            result.addProperty("added", amount);
            result.addProperty("currentEnergy", energyService.getEnergy(playerId));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("admin.server_error", e.getMessage()));
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
                result.addProperty("message", I18n.t("admin.the_reduction_amount_must"));
                return result;
            }

            PlayerService playerService = ServiceRegistry.getPlayerService();
            var p = playerService.getPlayerById(playerId);
            if (p == null) {
                result.addProperty("code", 404);
                result.addProperty("message", I18n.t("admin.player_not_found_2", playerId));
                return result;
            }

            EnergyService energyService = ServiceRegistry.getEnergyService();
            long current = energyService.getEnergy(playerId);
            if (current < amount) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("admin.not_enough_energy", current, amount));
                return result;
            }

            energyService.removeEnergy(playerId, amount);

            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.reduced_successfully"));
            result.addProperty("playerId", playerId);
            result.addProperty("playerName", p.getName());
            result.addProperty("removed", amount);
            result.addProperty("currentEnergy", energyService.getEnergy(playerId));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("admin.server_error", e.getMessage()));
        }
        return result;
    }
}
