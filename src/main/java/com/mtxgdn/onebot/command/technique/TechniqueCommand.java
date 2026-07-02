package com.mtxgdn.onebot.command.technique;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Technique;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.List;
import java.util.Map;

public class TechniqueCommand extends Command {
    public TechniqueCommand() {
        super(new String[]{"功法", "technique", "tj"}, "功法系统 — 学习/装备/卸下/升级功法",
                "/功法 [列表|我的|学习|装备|卸下|升级]", "战斗", null);

        registerSub(new String[]{"列表", "list"}, this::listAll);
        registerSub(new String[]{"我的", "my", "mine"}, this::myTechniques);
        registerSub(new String[]{"学习", "learn"}, this::learnTechnique);
        registerSub(new String[]{"装备", "equip"}, this::equipTechnique);
        registerSub(new String[]{"卸下", "unequip", "uninstall"}, this::unequipTechnique);
        registerSub(new String[]{"升级", "upgrade"}, this::upgradeTechnique);

        addRoute(RouteDefinition.get("technique/list", this::handleListHttp));
        addRoute(RouteDefinition.get("technique/my", this::handleMyHttp));
        addRoute(RouteDefinition.post("technique/learn", this::handleLearnHttp));
        addRoute(RouteDefinition.post("technique/equip", this::handleEquipHttp));
        addRoute(RouteDefinition.post("technique/unequip", this::handleUnequipHttp));
        addRoute(RouteDefinition.post("technique/upgrade", this::handleUpgradeHttp));
    }

    private void listAll(CommandContext ctx, PlayerInfo p, String[] parts) {
        var service = ServiceRegistry.getTechniqueService();
        List<Technique> techniques = service.getAllTechniques();
        StringBuilder sb = new StringBuilder();
        sb.append("=== 功法大全 ===\n");
        for (Technique t : techniques) {
            sb.append("[").append(t.getId()).append("] ").append(t.getName())
              .append(" (需境界Lv").append(t.getRequiredRealm()).append(")\n");
            sb.append("  类型:").append(typeLabel(t.getType()))
              .append(" | 学习: ").append(t.getLearnCostGold()).append("金").append(t.getLearnCostSpiritStones()).append("灵石")
              .append(" | Lv").append(t.getMaxLevel()).append("\n");
            sb.append("  ").append(t.getDescription()).append("\n\n");
        }
        sb.append("学习: /功法 学习 <功法ID>");
        ctx.reply(sb.toString());
    }

    private void myTechniques(CommandContext ctx, PlayerInfo p, String[] parts) {
        var service = ServiceRegistry.getTechniqueService();
        List<Technique> techniques = service.getPlayerTechniques(p.getId());
        StringBuilder sb = new StringBuilder();
        sb.append("你的功法\n");
        if (techniques.isEmpty()) {
            sb.append("尚未学习任何功法。\n使用 /功法 列表 查看可学习的功法");
        } else {
            sb.append("（最多同时装备3门）\n\n");
            for (Technique t : techniques) {
                sb.append("[").append(t.getId()).append("] ").append(t.getName());
                sb.append(" Lv.").append(t.getLevel()).append("/").append(t.getMaxLevel());
                if (t.isEquipped()) sb.append(" ⚡运转中");
                sb.append("\n");
            }
            sb.append("\n装备: /功法 装备 <ID> | 卸下: /功法 卸下 <ID> | 升级: /功法 升级 <ID>");
        }
        ctx.reply(sb.toString());
    }

    private void learnTechnique(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (parts.length < 2) { ctx.reply("用法: /功法 学习 <功法ID>\n先用 /功法 列表 查看功法ID"); return; }
        long techniqueId;
        try { techniqueId = Long.parseLong(parts[1]); } catch (NumberFormatException e) { ctx.reply("功法ID无效"); return; }

        var service = ServiceRegistry.getTechniqueService();
        Map<String, Object> result = service.learnTechnique(p.getId(), techniqueId);
        ctx.reply((String) result.get("message"));
    }

    private void equipTechnique(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (parts.length < 2) { ctx.reply("用法: /功法 装备 <功法ID>"); return; }
        long techniqueId;
        try { techniqueId = Long.parseLong(parts[1]); } catch (NumberFormatException e) { ctx.reply("功法ID无效"); return; }

        var service = ServiceRegistry.getTechniqueService();
        Map<String, Object> result = service.equipTechnique(p.getId(), techniqueId);
        ctx.reply((String) result.get("message"));
    }

    private void unequipTechnique(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (parts.length < 2) { ctx.reply("用法: /功法 卸下 <功法ID>"); return; }
        long techniqueId;
        try { techniqueId = Long.parseLong(parts[1]); } catch (NumberFormatException e) { ctx.reply("功法ID无效"); return; }

        var service = ServiceRegistry.getTechniqueService();
        Map<String, Object> result = service.unequipTechnique(p.getId(), techniqueId);
        ctx.reply((String) result.get("message"));
    }

    private void upgradeTechnique(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (parts.length < 2) { ctx.reply("用法: /功法 升级 <功法ID>"); return; }
        long techniqueId;
        try { techniqueId = Long.parseLong(parts[1]); } catch (NumberFormatException e) { ctx.reply("功法ID无效"); return; }

        var service = ServiceRegistry.getTechniqueService();
        Map<String, Object> result = service.upgradeTechnique(p.getId(), techniqueId);
        ctx.reply((String) result.get("message"));
    }

    private String typeLabel(Technique.Type type) {
        return switch (type) {
            case CULTIVATION -> "修炼";
            case ATTACK -> "攻击";
            case DEFENSE -> "防御";
            case UTILITY -> "辅助";
        };
    }

    // ==================== REST API ====================

    private JsonObject handleListHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            var service = ServiceRegistry.getTechniqueService();
            List<Technique> techniques = service.getAllTechniques();
            JsonArray arr = new JsonArray();
            for (Technique t : techniques) {
                JsonObject o = new JsonObject();
                o.addProperty("id", t.getId());
                o.addProperty("name", t.getName());
                o.addProperty("description", t.getDescription());
                o.addProperty("requiredRealm", t.getRequiredRealm());
                o.addProperty("learnCostGold", t.getLearnCostGold());
                o.addProperty("learnCostSpiritStones", t.getLearnCostSpiritStones());
                o.addProperty("upgradeBaseCostGold", t.getUpgradeBaseCostGold());
                o.addProperty("upgradeBaseCostSpiritStones", t.getUpgradeBaseCostSpiritStones());
                o.addProperty("type", t.getType().name());
                o.addProperty("maxLevel", t.getMaxLevel());
                o.addProperty("hpBonus", t.getHpBonus());
                o.addProperty("mpBonus", t.getMpBonus());
                o.addProperty("attackBonus", t.getAttackBonus());
                o.addProperty("defenseBonus", t.getDefenseBonus());
                o.addProperty("speedBonus", t.getSpeedBonus());
                o.addProperty("spiritBonus", t.getSpiritBonus());
                o.addProperty("cultivationSpeedBonus", t.getCultivationSpeedBonus());
                o.addProperty("expBonus", t.getExpBonus());
                o.addProperty("combatDamageBonus", t.getCombatDamageBonus());
                o.addProperty("damageReduction", t.getDamageReduction());
                arr.add(o);
            }
            result.addProperty("code", 200);
            result.add("techniques", arr);
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }

    private JsonObject handleMyHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            var service = ServiceRegistry.getTechniqueService();
            List<Technique> techniques = service.getPlayerTechniques(ctx.playerId());
            JsonArray arr = new JsonArray();
            for (Technique t : techniques) {
                JsonObject o = new JsonObject();
                o.addProperty("id", t.getId());
                o.addProperty("name", t.getName());
                o.addProperty("level", t.getLevel());
                o.addProperty("maxLevel", t.getMaxLevel());
                o.addProperty("proficiency", t.getProficiency());
                o.addProperty("isEquipped", t.isEquipped());
                o.addProperty("type", t.getType().name());
                arr.add(o);
            }
            result.addProperty("code", 200);
            result.add("techniques", arr);
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }

    private JsonObject handleLearnHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            JsonObject req = ctx.bodyJson();
            long techniqueId = req.has("techniqueId") ? req.get("techniqueId").getAsLong() : 0;
            if (techniqueId <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", "请提供有效的功法ID(techniqueId)");
                return result;
            }
            var service = ServiceRegistry.getTechniqueService();
            Map<String, Object> data = service.learnTechnique(ctx.playerId(), techniqueId);
            result.addProperty("code", (boolean) data.get("success") ? 200 : 400);
            result.addProperty("success", (boolean) data.get("success"));
            result.addProperty("message", (String) data.get("message"));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }

    private JsonObject handleEquipHttp(RouteDefinition.RestContext ctx) {
        return simplePostOp(ctx, "equip");
    }

    private JsonObject handleUnequipHttp(RouteDefinition.RestContext ctx) {
        return simplePostOp(ctx, "unequip");
    }

    private JsonObject handleUpgradeHttp(RouteDefinition.RestContext ctx) {
        return simplePostOp(ctx, "upgrade");
    }

    private JsonObject simplePostOp(RouteDefinition.RestContext ctx, String op) {
        JsonObject result = new JsonObject();
        try {
            JsonObject req = ctx.bodyJson();
            long techniqueId = req.has("techniqueId") ? req.get("techniqueId").getAsLong() : 0;
            if (techniqueId <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", "请提供有效的功法ID(techniqueId)");
                return result;
            }
            var service = ServiceRegistry.getTechniqueService();
            Map<String, Object> data = switch (op) {
                case "equip" -> service.equipTechnique(ctx.playerId(), techniqueId);
                case "unequip" -> service.unequipTechnique(ctx.playerId(), techniqueId);
                case "upgrade" -> service.upgradeTechnique(ctx.playerId(), techniqueId);
                default -> null;
            };
            if (data != null) {
                result.addProperty("code", (boolean) data.get("success") ? 200 : 400);
                result.addProperty("success", (boolean) data.get("success"));
                result.addProperty("message", (String) data.get("message"));
            }
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }
}
