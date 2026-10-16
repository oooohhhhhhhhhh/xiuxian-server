package com.mtxgdn.onebot.command.player;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.CommandUtils;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Title;
import com.mtxgdn.game.service.OfflineRewardService;
import com.mtxgdn.common.service.ServiceRegistry;

public class StatusCommand extends Command {

    public StatusCommand() {
        super(new String[]{"状态", "信息", "status", "info"},
                "查看角色状态（含离线收益）",
                "/状态",
                "我的角色",
                "game.player.info");
        setModule("player");
    }

    @Override
    public void execute(CommandContext ctx) {
        PlayerInfo p = requirePlayerInfo(ctx);
        if (p == null) return;

        Long userId = ctx.requireBinding();
        OfflineRewardService.OfflineRewardResult offlineReward =
                new OfflineRewardService().processOfflineRewards(userId);

        StringBuilder sb = new StringBuilder();

        Title activeTitle = ServiceRegistry.getTitleService().getEquippedTitle(p.getId());
        if (activeTitle != null) {
            sb.append("称号: ").append(activeTitle.getRarityColor())
              .append(activeTitle.getName()).append(" [").append(activeTitle.getRarityLabel()).append("]\n");
        }

        sb.append(CommandContext.formatPlayerStatus(p));

        if (offlineReward.hasReward) {
            sb.append("\n\n=== 离线收益 ===");
            sb.append("\n离线时长: ").append(CommandUtils.formatDuration(offlineReward.offlineSeconds));
            if (offlineReward.hpRecovered > 0) {
                sb.append("\n生命回复: +").append(offlineReward.hpRecovered);
            }
            if (offlineReward.mpRecovered > 0) {
                sb.append("\n法力回复: +").append(offlineReward.mpRecovered);
            }
            if (offlineReward.wasCultivating) {
                sb.append("\n离线修炼: +").append(offlineReward.expGained).append(" 经验");
                if (offlineReward.heartDemonTriggered) {
                    sb.append("\n⚠ 心魔入侵！损失 ").append(offlineReward.heartDemonExpLost).append(" 经验");
                }
            }
        }

        ctx.reply(sb.toString());
    }
}
