package com.mtxgdn.onebot.command.formation;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.Formation;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.FormationService;

public class FormationCommand extends Command {

    private final FormationService formationService = new FormationService();

    public FormationCommand() {
        super(new String[]{"阵法", "formation"},
                "阵法系统",
                "/阵法 <子命令> [参数]",
                "阵法",
                "game.cultivate");

        registerSub(new String[]{"place", "布置"}, this::handlePlace);
        registerSub(new String[]{"info", "信息"}, this::handleInfo);
        registerSub(new String[]{"upgrade", "升级"}, this::handleUpgrade);
        registerSub(new String[]{"remove", "拆除", "移除"}, this::handleRemove);
        registerSub(new String[]{"list", "列表"}, this::handleList);
        registerSub(new String[]{"help", "帮助", "?"}, this::handleHelp);
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
            ctx.reply("请指定阵法名，如: /阵法 place 聚灵阵");
            return;
        }

        String formationName = parts[1];
        String formationKey = getKeyByName(formationName);
        if (formationKey == null) {
            ctx.reply("未知阵法: " + formationName + "\n可用阵法: 聚灵阵, 护山大阵, 迷踪阵, 净化阵, 时间加速阵");
            return;
        }

        int level = 1;
        if (parts.length >= 3) {
            try {
                level = Integer.parseInt(parts[2]);
                if (level < 1 || level > Formation.MAX_LEVEL) {
                    ctx.reply("阵法等级必须在 1-" + Formation.MAX_LEVEL + " 之间");
                    return;
                }
            } catch (NumberFormatException e) {
                ctx.reply("无效的等级");
                return;
            }
        }

        var result = formationService.placeFormation(p.getId(), formationKey, level);
        ctx.reply((String) result.get("message"));
    }

    private void handleInfo(CommandContext ctx, PlayerInfo p, String[] parts) {
        Formation f = formationService.getActiveFormation(p.getId());
        if (f == null) {
            ctx.reply("你还没有布置阵法");
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