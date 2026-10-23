package com.mtxgdn.onebot.command.team;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.SecretRealmResult;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.game.service.SecretRealmService;
import com.mtxgdn.game.service.TeamService;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.List;
import com.mtxgdn.i18n.I18n;

/**
 * 组队系统 QQ 指令：创建/邀请/接受/拒绝/退出队伍，并进入团队副本。
 * 此前组队只有 REST 入口，QQ 玩家无法使用，此命令补齐主要入口。
 */
public class TeamCommand extends Command {
    public TeamCommand() {
        super(new String[]{"组队", "team"},
                "组队系统（2-5人，可挑战团队副本）",
                "/组队 创建 | /组队 邀请 <玩家名> | /组队 接受 | /组队 拒绝 | /组队 退出 | /组队 信息 | /组队 副本 <秘境名>",
                "宗门",
                null);
        
        setI18nKeys("cmd.team.desc", "cmd.team.usage");
    setModule("team");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        String arg = ctx.getArg();
        if (arg == null || arg.isBlank()) {
            ctx.reply(getUsage());
            return;
        }

        String[] parts = arg.trim().split("\\s+", 2);
        String sub = parts[0];
        String param = parts.length > 1 ? parts[1] : null;

        TeamService teamService = TeamService.getInstance();

        switch (sub) {
            case "创建" -> {
                TeamService.Team team = teamService.createTeam(p.getId());
                if (team == null) {
                    ctx.reply(I18n.t("team.failed_to_create_the_team"));
                } else {
                    ctx.reply(I18n.t("team.team_created", team.getTeamId()));
                }
            }
            case "邀请" -> {
                if (param == null || param.isBlank()) {
                    ctx.reply(I18n.t("team.usage"));
                    return;
                }
                PlayerService playerService = ServiceRegistry.getPlayerService();
                List<PlayerInfo> targets = playerService.searchPlayersByName(param.trim(), 1, 0);
                if (targets.isEmpty()) {
                    ctx.reply(I18n.t("team.player_does_not_exist", param));
                    return;
                }
                PlayerInfo target = targets.get(0);
                if (target.getId() == p.getId()) {
                    ctx.reply(I18n.t("team.you_cannot_invite_yourself"));
                    return;
                }
                if (teamService.invitePlayer(p.getId(), target.getId())) {
                    ctx.reply(I18n.t("team.team_invite_sent", target.getName()));
                } else {
                    ctx.reply(I18n.t("team.invite_failed_2"));
                }
            }
            case "接受" -> {
                TeamService.Team team = teamService.acceptInvite(p.getId());
                if (team == null) {
                    ctx.reply(I18n.t("team.there_are_no_pending_team"));
                } else {
                    ctx.reply(I18n.t("team.you_joined_the_team", team.getTeamId()));
                }
            }
            case "拒绝" -> {
                teamService.declineInvite(p.getId());
                ctx.reply(I18n.t("team.team_invite_rejected"));
            }
            case "退出" -> {
                if (teamService.leaveTeam(p.getId())) {
                    ctx.reply(I18n.t("team.you_have_left_the_team"));
                } else {
                    ctx.reply(I18n.t("team.you_are_not_in_any_2"));
                }
            }
            case "信息" -> {
                TeamService.Team team = teamService.getTeam(p.getId());
                if (team == null) {
                    ctx.reply(I18n.t("team.you_are_not_in_any"));
                    return;
                }
                PlayerService playerService = ServiceRegistry.getPlayerService();
                StringBuilder sb = new StringBuilder("👥 队伍信息\n队伍ID: ").append(team.getTeamId())
                        .append("\n队长ID: ").append(team.getLeaderId())
                        .append("\n人数: ").append(team.getMemberCount()).append("/5\n成员: ");
                for (long memberId : team.getMemberIds()) {
                    PlayerInfo m = playerService.getPlayerInfoById(memberId);
                    sb.append(m != null ? m.getName() : memberId).append(" ");
                }
                sb.append("\n\n副本挑战: /组队 副本 <秘境名>（需至少2人）");
                ctx.reply(sb.toString());
            }
            case "副本" -> {
                if (param == null || param.isBlank()) {
                    ctx.reply(I18n.t("team.usage_2"));
                    return;
                }
                SecretRealmResult result = ServiceRegistry.getSecretRealmService().enterRaid(p.getId(), param.trim());
                StringBuilder sb = new StringBuilder();
                if (result.isSuccess()) {
                    if (result.getLog() != null) {
                        for (String line : result.getLog()) {
                            sb.append(line).append("\n");
                        }
                    }
                    if (result.getMessage() != null) sb.append(result.getMessage());
                } else {
                    sb.append(result.getMessage());
                }
                ctx.reply(sb.toString());
            }
            default -> ctx.reply(getUsage());
        }
    }
}
