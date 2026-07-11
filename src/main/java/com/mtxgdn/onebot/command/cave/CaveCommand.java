package com.mtxgdn.onebot.command.cave;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.Cave;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.CaveService;

public class CaveCommand extends Command {
    private static final CaveService caveService = new CaveService();

    public CaveCommand() {
        super(new String[]{"洞府", "cave"},
                "洞府管理：修炼、收集灵气、升级洞府",
                "/洞府 <子命令> [参数]", "洞府", "game.cave.manage");

        registerSub(new String[]{"create", "开辟"}, this::handleCreate);
        registerSub(new String[]{"info", "信息"}, this::handleInfo);
        registerSub(new String[]{"collect", "收集"}, this::handleCollect);
        registerSub(new String[]{"meditate", "冥想"}, this::handleMeditate);
        registerSub(new String[]{"levelup", "升级"}, this::handleLevelUp);
        registerSub(new String[]{"help", "帮助", "?"}, this::handleHelp);
    }

    @Override
    protected void onDefault(CommandContext ctx, PlayerInfo p) {
        ctx.reply(buildOverview(p));
    }

    @Override
    protected void onUnknown(CommandContext ctx, PlayerInfo p, String sub, String[] parts) {
        ctx.reply(buildOverview(p));
    }

    private void handleHelp(CommandContext ctx, PlayerInfo p, String[] parts) {
        ctx.reply(buildHelp());
    }

    private void handleCreate(CommandContext ctx, PlayerInfo p, String[] parts) {
        String name = parts.length > 1 ? parts[1].trim() : "";
        var result = caveService.createCave(p.getId(), name);
        ctx.reply((String) result.get("message"));
    }

    private void handleInfo(CommandContext ctx, PlayerInfo p, String[] parts) {
        Cave cave = caveService.getCaveByPlayerId(p.getId());
        if (cave == null) {
            ctx.reply("你还没有洞府。\n使用 /洞府 create [名称] 开辟洞府（需要筑基期+300灵石）");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("===== 【").append(cave.getName()).append("】 =====\n");
        sb.append("等级: ").append(cave.getLevel()).append("级\n");
        sb.append("灵气: ").append(cave.getSpiritEnergy()).append("/").append(cave.getMaxSpiritEnergy()).append("\n");
        sb.append("修炼加成: +").append(cave.getCultivationBonus()).append("%\n");
        sb.append("存储加成: +").append(cave.getStorageBonus()).append(" 格\n");
        if (cave.getLevel() < Cave.MAX_LEVEL) {
            long cost = Cave.getLevelUpCost(cave.getLevel());
            sb.append("升级所需灵石: ").append(cost).append("\n");
        }
        sb.append("\n可用操作:\n");
        sb.append("  /洞府 collect   收集灵气\n");
        sb.append("  /洞府 meditate  洞中冥想\n");
        sb.append("  /洞府 levelup   升级洞府");
        ctx.reply(sb.toString());
    }

    private void handleCollect(CommandContext ctx, PlayerInfo p, String[] parts) {
        var result = caveService.collectSpiritEnergy(p.getId());
        ctx.reply((String) result.get("message"));
    }

    private void handleMeditate(CommandContext ctx, PlayerInfo p, String[] parts) {
        var result = caveService.meditate(p.getId());
        ctx.reply((String) result.get("message"));
    }

    private void handleLevelUp(CommandContext ctx, PlayerInfo p, String[] parts) {
        var result = caveService.levelUp(p.getId());
        ctx.reply((String) result.get("message"));
    }

    private String buildOverview(PlayerInfo p) {
        Cave cave = caveService.getCaveByPlayerId(p.getId());
        if (cave != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("════ 【").append(cave.getName()).append("】 ════\n");
            sb.append("等级: ").append(cave.getLevel());
            sb.append("  灵气: ").append(cave.getSpiritEnergy()).append("/").append(cave.getMaxSpiritEnergy());
            sb.append("\n修炼加成: +").append(cave.getCultivationBonus()).append("%");
            sb.append("  存储加成: +").append(cave.getStorageBonus()).append("格");
            sb.append("\n\n▍快捷操作\n");
            sb.append("  /洞府 collect   收集灵气\n");
            sb.append("  /洞府 meditate  洞中冥想\n");
            sb.append("  /洞府 levelup   升级洞府");
            return sb.toString();
        } else {
            return """
════ 洞府系统 ════

洞府是修仙者的修炼圣地，可提供修炼加成和灵气收集。

▍开辟洞府
  /洞府 create [名称]    开辟洞府（筑基期+300灵石）

▍洞府功能
  修炼加成   - 洞府等级越高，修炼速度越快
  灵气收集   - 洞府会自动汇聚天地灵气，可定期收集转化为灵石
  存储扩展   - 洞府提供额外的存储空间

输入 /洞府 help 查看全部命令""";
        }
    }

    private String buildHelp() {
        return """
===== 洞府系统 =====
/洞府 create [名称]      开辟洞府（筑基期，300灵石）
/洞府 info               查看洞府信息
/洞府 collect            收集洞府灵气（每5分钟可收集一次）
/洞府 meditate           在洞府中冥想修炼（获得经验）
/洞府 levelup            升级洞府（消耗灵石）
/洞府 help               显示帮助""";
    }
}