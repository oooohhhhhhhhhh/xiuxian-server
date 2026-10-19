package com.mtxgdn.onebot.command.formation;

import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.Formation;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.FormationService;
import com.mtxgdn.i18n.I18n;

public class FormationCommand extends Command {

    private final FormationService formationService = new FormationService();

    public FormationCommand() {
        super(new String[]{"阵法", "formation"},
                "阵法系统",
                "/阵法 <子命令> [参数]",
                "阵法",
                "game.cultivate");
        
        setI18nKeys("cmd.formation.desc", "cmd.formation.usage");
    setModule("formation");

        registerSub(new String[]{"place", "布置"}, this::handlePlace);
        registerSub(new String[]{"info", "信息"}, this::handleInfo);
        registerSub(new String[]{"upgrade", "升级"}, this::handleUpgrade);
        registerSub(new String[]{"remove", "拆除", "移除"}, this::handleRemove);
        registerSub(new String[]{"list", "列表"}, this::handleList);
        registerSub(new String[]{"help", "帮助", "?"}, this::handleHelp);

        // HTTP 路由补齐：此前阵法只有 QQ 子命令、没有任何 addRoute，
        // 且 ModuleGateFilter 里没有 formation 映射，Web/REST 通道完全访问不到阵法系统。
        addRoute(RouteDefinition.get("formation/info", "game.cultivate", this::httpInfo));
        addRoute(RouteDefinition.get("formation/list", "game.cultivate", this::httpList));
        addRoute(RouteDefinition.post("formation/place", "game.cultivate", this::httpPlace));
        addRoute(RouteDefinition.post("formation/upgrade", "game.cultivate", this::httpUpgrade));
        addRoute(RouteDefinition.post("formation/remove", "game.cultivate", this::httpRemove));
    }

    // ==================== HTTP 处理器 ====================

    private JsonObject httpInfo(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            Formation f = formationService.getActiveFormation(ctx.playerId());
            if (f == null) {
                result.addProperty("code", 404);
                result.addProperty("success", false);
                result.addProperty("message", I18n.t("formation.hcd888a30"));
                result.addProperty("hasFormation", false);
                return result;
            }
            result.addProperty("code", 200);
            result.addProperty("success", true);
            result.addProperty("hasFormation", true);
            result.addProperty("name", f.getName());
            result.addProperty("formationKey", f.getFormationKey());
            result.addProperty("level", f.getLevel());
            result.addProperty("maxLevel", Formation.MAX_LEVEL);
            result.addProperty("remainingSeconds", f.getRemainingSeconds());
            result.addProperty("spiritEnergyBoost", f.getSpiritEnergyBoost());
            result.addProperty("cultivationBonus", f.getCultivationBonus());
            result.addProperty("defenseBonus", f.getDefenseBonus());
            result.addProperty("heartDemonResist", f.getHeartDemonResist());
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("success", false);
            result.addProperty("message", I18n.t("formation.hf186bd38", e.getMessage()));
        }
        return result;
    }

    private JsonObject httpList(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            result.addProperty("code", 200);
            result.addProperty("success", true);
            // 可用阵法清单（与 QQ 端 handleList 保持一致）
            var arr = new com.google.gson.JsonArray();
            String[][] defs = {
                    {"spirit_gathering", "聚灵阵", "加速灵气汇聚，提升修炼速度"},
                    {"mountain_protection", "护山大阵", "增加防御"},
                    {"maze", "迷踪阵", "迷惑敌人"},
                    {"purification", "净化阵", "减少心魔触发"},
                    {"time_acceleration", "时间加速阵", "大幅提升修炼速度，持续时间短"},
            };
            for (String[] d : defs) {
                JsonObject o = new JsonObject();
                o.addProperty("key", d[0]);
                o.addProperty("name", d[1]);
                o.addProperty("description", d[2]);
                o.addProperty("maxLevel", Formation.MAX_LEVEL);
                for (int lv = 1; lv <= Formation.MAX_LEVEL; lv++) {
                    o.addProperty("placeCostLv" + lv, Formation.getPlaceCost(d[0], lv));
                }
                arr.add(o);
            }
            result.add("formations", arr);
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("success", false);
            result.addProperty("message", I18n.t("formation.hf186bd38", e.getMessage()));
        }
        return result;
    }

    private JsonObject httpPlace(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            JsonObject req = ctx.bodyJson();
            // 接受 formationKey（英文键）或 name（中文名）两种写法
            String key = null;
            if (req.has("formationKey") && !req.get("formationKey").isJsonNull()) {
                key = req.get("formationKey").getAsString().trim();
            } else if (req.has("name") && !req.get("name").isJsonNull()) {
                key = getKeyByName(req.get("name").getAsString().trim());
            }
            if (key == null || key.isBlank()) {
                result.addProperty("code", 400);
                result.addProperty("success", false);
                result.addProperty("message", I18n.t("formation.invalid_key"));
                return result;
            }

            int level = 1;
            if (req.has("level") && !req.get("level").isJsonNull()) {
                level = req.get("level").getAsInt();
                if (level < 1 || level > Formation.MAX_LEVEL) {
                    result.addProperty("code", 400);
                    result.addProperty("success", false);
                    result.addProperty("message", I18n.t("formation.h567eb358", Formation.MAX_LEVEL));
                    return result;
                }
            }

            var data = formationService.placeFormation(ctx.playerId(), key, level);
            boolean ok = Boolean.TRUE.equals(data.get("success"));
            result.addProperty("code", ok ? 200 : 400);
            result.addProperty("success", ok);
            result.addProperty("message", String.valueOf(data.get("message")));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("success", false);
            result.addProperty("message", I18n.t("formation.hf186bd38", e.getMessage()));
        }
        return result;
    }

    private JsonObject httpUpgrade(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            var data = formationService.upgradeFormation(ctx.playerId());
            boolean ok = Boolean.TRUE.equals(data.get("success"));
            result.addProperty("code", ok ? 200 : 400);
            result.addProperty("success", ok);
            result.addProperty("message", String.valueOf(data.get("message")));
            Object nl = data.get("newLevel");
            if (nl instanceof Number n) result.addProperty("newLevel", n);
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("success", false);
            result.addProperty("message", I18n.t("formation.hf186bd38", e.getMessage()));
        }
        return result;
    }

    private JsonObject httpRemove(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            var data = formationService.removeFormation(ctx.playerId());
            boolean ok = Boolean.TRUE.equals(data.get("success"));
            result.addProperty("code", ok ? 200 : 400);
            result.addProperty("success", ok);
            result.addProperty("message", String.valueOf(data.get("message")));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("success", false);
            result.addProperty("message", I18n.t("formation.hf186bd38", e.getMessage()));
        }
        return result;
    }

    @Override
    protected void onDefault(CommandContext ctx, PlayerInfo p) {
        ctx.reply(buildHelp());
    }

    @Override
    protected void onUnknown(CommandContext ctx, PlayerInfo p, String sub, String[] parts) {
        ctx.reply(buildHelp());
    }

    private void handleHelp(CommandContext ctx, PlayerInfo p, String[] parts) {
        ctx.reply(buildHelp());
    }

    private void handlePlace(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (parts.length < 2) {
            ctx.reply(I18n.t("formation.h169acfb8"));
            return;
        }

        String formationName = parts[1];
        String formationKey = getKeyByName(formationName);
        if (formationKey == null) {
            ctx.reply(I18n.t("formation.hf213bc30", formationName));
            return;
        }

        int level = 1;
        if (parts.length >= 3) {
            try {
                level = Integer.parseInt(parts[2]);
                if (level < 1 || level > Formation.MAX_LEVEL) {
                    ctx.reply(I18n.t("formation.h567eb358", Formation.MAX_LEVEL));
                    return;
                }
            } catch (NumberFormatException e) {
                ctx.reply(I18n.t("formation.h5522cfdc"));
                return;
            }
        }

        var result = formationService.placeFormation(p.getId(), formationKey, level);
        ctx.reply((String) result.get("message"));
    }

    private void handleInfo(CommandContext ctx, PlayerInfo p, String[] parts) {
        Formation f = formationService.getActiveFormation(p.getId());
        if (f == null) {
            ctx.reply(I18n.t("formation.hcd888a30"));
            return;
        }

        long remaining = f.getRemainingSeconds();
        int minutes = (int) (remaining / 60);
        int seconds = (int) (remaining % 60);

        StringBuilder msg = new StringBuilder();
        msg.append("===== 阵法信息 =====\n");
        msg.append("名称: ").append(f.getName()).append("\n");
        msg.append("等级: ").append(f.getLevel()).append("/").append(Formation.MAX_LEVEL).append("\n");
        msg.append("剩余时间: ").append(minutes).append("分").append(seconds).append("秒\n");
        if (f.getSpiritEnergyBoost() > 0) {
            msg.append("灵气汇聚: +").append(f.getSpiritEnergyBoost()).append("%\n");
        }
        if (f.getCultivationBonus() > 0) {
            msg.append("修炼加成: +").append(f.getCultivationBonus()).append("%\n");
        }
        if (f.getDefenseBonus() > 0) {
            msg.append("防御加成: +").append(f.getDefenseBonus()).append("%\n");
        }
        if (f.getHeartDemonResist() > 0) {
            msg.append("心魔抵抗: +").append(f.getHeartDemonResist()).append("%\n");
        }
        ctx.reply(msg.toString());
    }

    private void handleUpgrade(CommandContext ctx, PlayerInfo p, String[] parts) {
        var result = formationService.upgradeFormation(p.getId());
        ctx.reply((String) result.get("message"));
    }

    private void handleRemove(CommandContext ctx, PlayerInfo p, String[] parts) {
        var result = formationService.removeFormation(p.getId());
        ctx.reply((String) result.get("message"));
    }

    private void handleList(CommandContext ctx, PlayerInfo p, String[] parts) {
        StringBuilder msg = new StringBuilder();
        msg.append("===== 可用阵法 =====\n");
        msg.append("🔮 聚灵阵 - 加速灵气汇聚，提升修炼速度\n");
        msg.append("🛡️ 护山大阵 - 增加防御\n");
        msg.append("🌀 迷踪阵 - 迷惑敌人\n");
        msg.append("✨ 净化阵 - 减少心魔触发\n");
        msg.append("⏱️ 时间加速阵 - 大幅提升修炼速度，持续时间短\n");
        ctx.reply(msg.toString());
    }

    private String buildHelp() {
        StringBuilder msg = new StringBuilder();
        msg.append("===== 阵法系统 =====\n");
        msg.append("可用指令:\n");
        msg.append("/阵法 place <阵法名> [等级] - 布置阵法\n");
        msg.append("/阵法 info - 查看当前阵法\n");
        msg.append("/阵法 upgrade - 升级阵法\n");
        msg.append("/阵法 remove - 拆除阵法\n");
        msg.append("/阵法 list - 查看可用阵法\n");
        return msg.toString();
    }

    private String getKeyByName(String name) {
        return switch (name) {
            case "聚灵阵" -> "spirit_gathering";
            case "护山大阵" -> "mountain_protection";
            case "迷踪阵" -> "maze";
            case "净化阵" -> "purification";
            case "时间加速阵" -> "time_acceleration";
            default -> null;
        };
    }
}