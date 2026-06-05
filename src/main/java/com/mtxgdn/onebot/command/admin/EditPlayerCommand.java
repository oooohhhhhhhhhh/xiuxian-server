package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;

public class EditPlayerCommand extends Command {
    public EditPlayerCommand() {
        super(new String[]{"编辑玩家", "editplayer", "修改玩家"},
                "修改玩家属性（仅私聊）",
                "/编辑玩家 <玩家ID> <属性>=<值> ...",
                "管理", "admin.status", true);
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = new QqBindingService().findByQq(ctx.getSenderId());
        if (b == null) {
            ctx.reply("请先绑定账号。");
            return;
        }
        if (!PermissionService.hasPermission(b.getUserId(), "admin.status")) {
            ctx.reply("权限不足，你无权使用此功能。");
            return;
        }

        String arg = ctx.getArg();
        if (arg == null || arg.isBlank()) {
            ctx.reply("用法: /编辑玩家 <玩家ID> <属性>=<值> ...\n"
                    + "可用属性: hp maxHp mp maxMp attack defense speed spirit level gold experience realm\n"
                    + "示例: /编辑玩家 1 hp=500 attack=100 level=20");
            return;
        }

        String[] parts = arg.trim().split("\\s+");
        if (parts.length < 2) {
            ctx.reply("参数不足。用法: /编辑玩家 <玩家ID> <属性>=<值> ...");
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

        StringBuilder changes = new StringBuilder();
        changes.append("修改 ").append(p.getName()).append(" (ID:").append(playerId).append("):\n");

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            int eqIdx = part.indexOf('=');
            if (eqIdx <= 0) continue;
            String key = part.substring(0, eqIdx);
            String valStr = part.substring(eqIdx + 1);

            try {
                switch (key) {
                    case "hp":
                        p.setHp(Integer.parseInt(valStr));
                        changes.append("  生命 = ").append(valStr).append("\n");
                        break;
                    case "maxHp":
                        p.setMaxHp(Integer.parseInt(valStr));
                        changes.append("  最大生命 = ").append(valStr).append("\n");
                        break;
                    case "mp":
                        p.setMp(Integer.parseInt(valStr));
                        changes.append("  法力 = ").append(valStr).append("\n");
                        break;
                    case "maxMp":
                        p.setMaxMp(Integer.parseInt(valStr));
                        changes.append("  最大法力 = ").append(valStr).append("\n");
                        break;
                    case "attack":
                        p.setAttack(Integer.parseInt(valStr));
                        changes.append("  攻击 = ").append(valStr).append("\n");
                        break;
                    case "defense":
                        p.setDefense(Integer.parseInt(valStr));
                        changes.append("  防御 = ").append(valStr).append("\n");
                        break;
                    case "speed":
                        p.setSpeed(Integer.parseInt(valStr));
                        changes.append("  速度 = ").append(valStr).append("\n");
                        break;
                    case "spirit":
                        p.setSpirit(Integer.parseInt(valStr));
                        changes.append("  神识 = ").append(valStr).append("\n");
                        break;
                    case "level":
                        p.setLevel(Integer.parseInt(valStr));
                        changes.append("  等级 = ").append(valStr).append("\n");
                        break;
                    case "gold":
                        p.setGold(Long.parseLong(valStr));
                        changes.append("  金币 = ").append(valStr).append("\n");
                        break;
                    case "experience":
                        p.setExperience(Long.parseLong(valStr));
                        changes.append("  灵力 = ").append(valStr).append("\n");
                        break;
                    case "realm":
                        p.setRealm(Integer.parseInt(valStr));
                        changes.append("  境界 = ").append(valStr).append("\n");
                        break;
                    default:
                        changes.append("  未知属性: ").append(key).append("\n");
                        break;
                }
            } catch (NumberFormatException e) {
                changes.append("  格式错误: ").append(part).append("\n");
            }
        }

        playerService.updatePlayer(playerId, p);
        ctx.reply(changes.toString());
    }
}
