package com.mtxgdn.onebot.command.cultivation;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.RealmBreakthroughResult;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.game.service.RealmService;
import com.mtxgdn.util.PlayerActionLogger;

import java.util.List;

/**
 * 境界突破 / 渡劫。
 *
 * <p>三条子路径：</p>
 * <ul>
 *   <li>{@code /突破} —— 冲击下一境界。小境界必成；大境界要渡劫，失败会损失修为并累积<b>连败增益</b>。</li>
 *   <li>{@code /突破 预览} —— 不掷骰，只把当前成功率的每一项构成摊开（含连败增益与凝实代价）。</li>
 *   <li>{@code /突破 凝实} —— 修为圆满时压制修为淬体（极境），用更难的渡劫换基础属性。</li>
 * </ul>
 */
public class BreakthroughCommand extends Command {

    public BreakthroughCommand() {
        super(new String[]{"突破", "breakthrough"},
                "突破当前境界",
                "/突破 - 冲击下一境界\n"
                        + "/突破 预览 - 查看本次渡劫的成功率构成（不消耗）\n"
                        + "/突破 凝实 - 修为圆满时压制修为淬体（极境，最多 3 层）",
                "修炼",
                "game.realm.breakthrough");
        setModule("cultivation");

        registerSub(new String[]{"预览", "preview", "概率", "几率"}, this::handlePreview);
        registerSub(new String[]{"凝实", "consolidate", "极境"}, this::handleConsolidate);
    }

    @Override
    protected void onDefault(CommandContext ctx, PlayerInfo p) {
        doBreakthrough(ctx, p);
    }

    @Override
    protected void onUnknown(CommandContext ctx, PlayerInfo p, String sub, String[] parts) {
        // 历史上 /突破 后面的参数是被忽略的，保持兼容：照常尝试突破
        doBreakthrough(ctx, p);
    }

    private void doBreakthrough(CommandContext ctx, PlayerInfo p) {
        var realmService = ServiceRegistry.getRealmService();
        var playerService = ServiceRegistry.getPlayerService();
        var actionLog = PlayerActionLogger.getInstance();
        Long userId = p.getUserId();

        RealmBreakthroughResult result = realmService.tryBreakthrough(userId);
        actionLog.logBreakthrough(userId, p.getName(), result.isSuccess(), result.getMessage());

        StringBuilder sb = new StringBuilder();
        sb.append("===== 境界突破 =====\n");
        sb.append(result.getMessage());

        // 渡劫过程与成功率构成一直算着却从没显示过 —— 玩家看不到自己离成功有多远，
        // 失败一次只会觉得"随机劝退"。这里全部摊开。
        appendSection(sb, "渡劫过程", result.getTribulationLog());
        appendSection(sb, "成功率构成", result.getSuccessRateBreakdown());

        if (result.isSuccess()) {
            sb.append("\n\n--- 境界提升 ---\n");
            sb.append("生命上限: +").append(result.getHpAdded()).append("\n");
            sb.append("法力上限: +").append(result.getMpAdded()).append("\n");
            sb.append("攻击力: +").append(result.getAttackAdded()).append("\n");
            sb.append("防御力: +").append(result.getDefenseAdded()).append("\n");
            sb.append("速度: +").append(result.getSpeedAdded()).append("\n");
            sb.append("神识: +").append(result.getSpiritAdded());

            PlayerInfo updated = playerService.getPlayerByUserId(userId);
            sb.append("\n\n").append(CommandContext.formatPlayerStatus(updated));

            NewbieGuideService.GuideResult guide = ServiceRegistry.getGuideService()
                    .checkAndAdvance((int) updated.getId(), updated, "breakthrough");
            if (guide.message != null) sb.append("\n\n💡 ").append(guide.message);
        } else if (result.isHasTribulation()) {
            sb.append("\n\n💡 连败增益会一直累积，用 /突破 预览 查看下次的真实成功率");
        }

        ctx.reply(sb.toString());
    }

    private void handlePreview(CommandContext ctx, PlayerInfo p, String[] parts) {
        RealmBreakthroughResult result = ServiceRegistry.getRealmService().previewBreakthrough(p.getUserId());
        StringBuilder sb = new StringBuilder("===== 突破预览 =====\n");
        sb.append(result.getMessage());
        appendSection(sb, "成功率构成", result.getSuccessRateBreakdown());
        appendConsolidationStatus(sb, p);
        ctx.reply(sb.toString());
    }

    private void handleConsolidate(CommandContext ctx, PlayerInfo p, String[] parts) {
        var realmService = ServiceRegistry.getRealmService();
        RealmBreakthroughResult result = realmService.consolidate(p.getUserId());
        StringBuilder sb = new StringBuilder("===== 极境凝实 =====\n");
        sb.append(result.getMessage());
        if (result.isSuccess()) {
            appendSection(sb, "过程", result.getTribulationLog());
            appendConsolidationStatus(sb, ServiceRegistry.getPlayerService().getPlayerByUserId(p.getUserId()));
        } else {
            appendConsolidationStatus(sb, p);
        }
        ctx.reply(sb.toString());
    }

    private void appendConsolidationStatus(StringBuilder sb, PlayerInfo p) {
        if (p == null) {
            return;
        }
        int layers = p.getConsolidation();
        int cap = RealmService.consolidateMaxLayers();
        int pity = p.getBreakthroughPity();
        sb.append("\n\n--- 当前状态 ---\n");
        sb.append("极境凝实: ").append(layers).append("/").append(cap);
        if (layers > 0) {
            sb.append("（成功率 ×").append(String.format("%.2f", (100 - layers * RealmService.consolidateRatePenalty()) / 100.0))
                    .append("，失败损失 +").append(String.format("%.0f", layers * RealmService.consolidatePunishBonus() * 100))
                    .append("%）");
        }
        sb.append("\n连败增益: ").append(pity).append(" 次");
        if (pity > 0) {
            sb.append("（下次渡劫 +").append(pity * RealmService.pityBonusPerFailure()).append("%）");
        }
    }

    private static void appendSection(StringBuilder sb, String title, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        sb.append("\n\n--- ").append(title).append(" ---");
        for (String line : lines) {
            sb.append("\n").append(line);
        }
    }
}
