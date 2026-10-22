package com.mtxgdn.onebot.command.pvp;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.BossForm;
import com.mtxgdn.game.entity.Monster;
import com.mtxgdn.game.entity.PveCombatResult;
import com.mtxgdn.game.entity.RaidCombatResult;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.CombatService;
import com.mtxgdn.game.service.PlayerService;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.mtxgdn.i18n.I18n;

public class PvpCommand extends Command {

    private final CombatService combatService;
    private final PlayerService playerService;

    private static final List<Monster> MONSTERS = Arrays.asList(
            new Monster("野猪精", 80, 20, 50, 3, 3, false,
                    new String[]{"mtxgdn:spirit_grass_seed", "mtxgdn:healing_pill"}, 0.3, 100, 20, 5, "一只体型庞大的野猪精，獠牙锋利"),
            new Monster("青狼", 100, 30, 70, 4, 4, false,
                    new String[]{"mtxgdn:spirit_grass_seed", "mtxgdn:healing_pill", "mtxgdn:mana_pill"}, 0.4, 150, 30, 8, "青面獠牙的巨狼，眼神凶狠"),
            new Monster("黑熊妖", 150, 60, 100, 5, 5, false,
                    new String[]{"mtxgdn:healing_pill", "mtxgdn:mana_pill", "mtxgdn:spirit_recovery_pill"}, 0.35, 200, 40, 10, "力大无穷的黑熊精"),
            new Monster("白骨精", 180, 40, 150, 6, 6, false,
                    new String[]{"mtxgdn:mana_pill", "mtxgdn:spirit_recovery_pill", "mtxgdn:cultivation_elixir"}, 0.3, 250, 50, 15, "阴森恐怖的骷髅妖怪")
    );

    private static final List<BossForm> BOSS_FORMS = Arrays.asList(
            new BossForm("千年妖王·人形", "妖王的人形形态，看似普通实则蕴含强大力量", 1.0, 1.0, 1.0, 1.0, 0.1, new String[0], 0.5),
            new BossForm("千年妖王·兽形", "妖王现出兽形，气势大增", 1.2, 1.3, 1.1, 0.9, 0.15, new String[0], 0.6),
            new BossForm("千年妖王·狂暴", "妖王进入狂暴状态，攻击力大幅提升", 1.5, 1.5, 1.3, 0.7, 0.2, new String[0], 0.8)
    );

    public PvpCommand() {
        super(new String[]{"决斗", "切磋", "fight", "战斗", "讨伐", "boss"},
                "决斗系统",
                "/决斗 <角色名> - 发起切磋\n/决斗 接受 - 接受挑战\n/决斗 拒绝 - 拒绝挑战\n/战斗 - 挑战野外怪物\n/讨伐 - 挑战Boss\n/boss - 发起团队Boss战",
                "战斗",
                null,
                false,
                true);
        
        setI18nKeys("cmd.pvp.desc", "cmd.pvp.usage");
    setModule("pvp");
        this.combatService = new CombatService();
        this.playerService = new PlayerService();
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        // 注意 id 域：CombatService 全族（acceptChallenge / rejectChallenge /
        // createChallenge / pveFight / raidBossFight）收的都是 playerId，
        // 而 ctx.requireBinding() 给的是 userId。两者在库里通常不相等，
        // 早期这里直接把 userId 传进去，导致整条指令族在 players.id != user_id 的库上失效。
        long playerId = p.getId();

        String arg = ctx.getArg();
        if (arg == null || arg.isBlank()) {
            ctx.reply(I18n.t("pvp.h6af3afb0", getUsage()));
            return;
        }

        String[] parts = arg.trim().split("\\s+", 2);
        String subCommand = parts[0];

        switch (subCommand) {
            case "接受":
                ctx.reply(handleAccept(playerId));
                break;
            case "拒绝":
                ctx.reply(handleReject(playerId));
                break;
            case "战斗":
                ctx.reply(handleFight(playerId));
                break;
            case "讨伐":
                ctx.reply(handleBossFight(playerId));
                break;
            case "boss":
                ctx.reply(handleRaidBoss(playerId, parts));
                break;
            default:
                ctx.reply(handleChallenge(playerId, subCommand));
                break;
        }
    }

    private String handleAccept(long playerId) {
        CombatService.CombatResult result = combatService.acceptChallenge(playerId);
        if (!result.isSuccess()) {
            return result.getMessage();
        }
        return formatPvpResult(result);
    }

    private String handleReject(long playerId) {
        String challengerName = combatService.rejectChallenge(playerId);
        if (challengerName == null) {
            return "没有待接受的挑战";
        }
        return "已拒绝【" + challengerName + "】的挑战";
    }

    private String handleChallenge(long playerId, String targetName) {
        List<PlayerInfo> targets = playerService.searchPlayersByName(targetName, 1, 0);
        if (targets.isEmpty()) return "角色【" + targetName + "】不存在";
        PlayerInfo target = targets.get(0);

        CombatService.CombatResult result = combatService.createChallenge(playerId, target.getId());
        if (!result.isSuccess()) {
            return result.getMessage();
        }

        if (result.isPending()) {
            return "挑战已发出，等待【" + targetName + "】回应...";
        }

        return formatPvpResult(result);
    }

    private String handleFight(long playerId) {
        PlayerInfo player = playerService.getPlayerInfoById(playerId);
        if (player == null) return "你还没有角色，请先注册";

        if (player.getHp() <= 0) return "你的生命值不足，无法战斗";

        Monster monster = MONSTERS.get((int)(Math.random() * MONSTERS.size()));
        monster = new Monster(monster.getName(), monster.getHp(), monster.getAttack(),
                monster.getDefense(), monster.getSpeed(), monster.getRealm(), monster.isBoss(),
                monster.getLootTable(), monster.getLootChance(), monster.getExpReward(),
                monster.getGoldReward(), monster.getSpiritStoneReward(), monster.getDescription());

        PveCombatResult result = combatService.pveFight(playerId, monster);
        return formatPveResult(result);
    }

    private String handleBossFight(long playerId) {
        PlayerInfo player = playerService.getPlayerInfoById(playerId);
        if (player == null) return "你还没有角色，请先注册";

        if (player.getHp() <= 0) return "你的生命值不足，无法战斗";

        Monster boss = new Monster("千年妖王", 500, 200, 100, 80, 5, true,
                new String[]{"mtxgdn:cultivation_elixir", "mtxgdn:spirit_sword"}, 0.5, 500, 100, 50,
                "千年妖王，修为深厚，已修炼出人形");

        PveCombatResult result = combatService.pveFight(playerId, boss);
        return formatPveResult(result);
    }

    private String handleRaidBoss(long playerId, String[] args) {
        PlayerInfo player = playerService.getPlayerInfoById(playerId);
        if (player == null) return "你还没有角色，请先注册";

        if (player.getHp() <= 0) return "你的生命值不足，无法参加Boss战";

        List<Long> playerIds = new ArrayList<>();
        // 这里必须是 playerId —— 同列表里其它成员来自 targets.get(0).getId()（也是 playerId），
        // 早先加入的是 userId，导致混入不同 id 域、raidBossFight 取到错误的角色。
        playerIds.add(playerId);

        if (args.length > 1) {
            String[] playerNames = args[1].split("\\s+");
            for (String name : playerNames) {
                List<PlayerInfo> targets = playerService.searchPlayersByName(name, 1, 0);
                if (!targets.isEmpty()) {
                    playerIds.add(targets.get(0).getId());
                }
            }
        }

        if (playerIds.size() < 2) {
            return "团队Boss战需要至少2名玩家！用法：/boss <玩家1> <玩家2> ...";
        }

        RaidCombatResult result = combatService.raidBossFight(playerIds, BOSS_FORMS);
        return formatRaidResult(result);
    }

    private String formatPvpResult(CombatService.CombatResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚔【").append(result.getChallengerName()).append("】VS【").append(result.getTargetName()).append("】\n");

        if (result.getBattleLog() != null) {
            for (String line : result.getBattleLog()) {
                sb.append(line).append("\n");
            }
        }

        if (result.isChallengerWon()) {
            sb.append("\n🏆 ").append(result.getChallengerName()).append(" 获胜！");
            if (result.getExpReward() > 0) sb.append(" 获得 ").append(result.getExpReward()).append(" 经验");
            if (result.getGoldReward() > 0) sb.append("，").append(result.getGoldReward()).append(" 金币");
        } else {
            sb.append("\n🏆 ").append(result.getTargetName()).append(" 获胜！");
        }

        return sb.toString();
    }

    private String formatPveResult(PveCombatResult result) {
        StringBuilder sb = new StringBuilder();

        if (result.getBattleLog() != null) {
            for (String line : result.getBattleLog()) {
                sb.append(line).append("\n");
            }
        }

        sb.append(result.getMessage());
        return sb.toString();
    }

    private String formatRaidResult(RaidCombatResult result) {
        StringBuilder sb = new StringBuilder();

        if (result.getBattleLog() != null) {
            for (String line : result.getBattleLog()) {
                sb.append(line).append("\n");
            }
        }

        sb.append(result.getMessage());
        return sb.toString();
    }
}