package com.mtxgdn.game.service.combat;



import java.util.ArrayList;
import java.util.List;

public class BattleLogGenerator {

    private final List<String> logs = new ArrayList<>();

    public void add(String message) {
        logs.add(message);
    }

    public void addRound(int round) {
        logs.add("-- 第" + round + "回合 --");
    }

    public void addVs(String attackerName, String defenderName) {
        logs.add("⚔【" + attackerName + "】VS【" + defenderName + "】");
        logs.add("");
    }

    public void addMonsterAppearance(String monsterName, String description, int attack, int defense, int hp) {
        logs.add("⚔ 前方突然窜出一只【" + monsterName + "】！");
        logs.add("【" + monsterName + "】- 攻击:" + attack + " 防御:" + defense + " 生命:" + hp);
        logs.add("---");
    }

    public void addBossAppearance(String bossName, String description) {
        logs.add("👑 前方出现了一股强大的气息——【" + bossName + "】！");
        logs.add(description);
        logs.add("---");
    }

    public void addSkillUse(String attackerName, String skillName, int skillLevel, boolean isCrit, String tacticText) {
        String critText = isCrit ? "，暴击！" : "";
        if (tacticText != null && !tacticText.isEmpty()) {
            logs.add("  " + attackerName + " " + tacticText + "使出「" + skillName + " Lv." + skillLevel + "」" + critText);
        } else {
            logs.add("  " + attackerName + " 使出「" + skillName + " Lv." + skillLevel + "」" + critText);
        }
    }

    public void addNormalAttack(String attackerName, boolean isCrit) {
        String critText = isCrit ? "，暴击！" : "";
        logs.add("  " + attackerName + " 挥剑斩去" + critText);
    }

    public void addPlayerSkillUse(String playerName, String skillName, int skillLevel, int mpCost) {
        logs.add("【" + playerName + "】使用了【" + skillName + " Lv." + skillLevel + "】（消耗 " + mpCost + " MP）！");
    }

    public void addPlayerNormalAttack(String playerName) {
        logs.add("【" + playerName + "】发动了普通攻击！");
    }

    public void addDamage(String targetName, int damage, int currentHp, int maxHp) {
        logs.add("  " + targetName + " 遭受 " + damage + " 点伤害 （HP: " + Math.max(0, currentHp) + "/" + maxHp + "）");
    }

    public void addMonsterDamage(String monsterName, int damage, int currentHp) {
        logs.add("【" + monsterName + "】受到 " + damage + " 点伤害，剩余生命 " + Math.max(0, currentHp));
    }

    public void addPlayerDamage(int damage, int currentHp) {
        logs.add("你受到 " + damage + " 点伤害，剩余生命 " + Math.max(0, currentHp));
    }

    public void addCrit() {
        logs.add("暴击！");
    }

    public void addVictory(String winnerName) {
        logs.add("");
        logs.add("🏆 " + winnerName + " 技高一筹，赢得了这场切磋！");
    }

    public void addDraw() {
        logs.add("");
        logs.add("🤝 双方势均力敌，以平局收场！");
    }

    public void addRounds(int rounds) {
        logs.add("共 " + rounds + " 回合");
    }

    public void addReward(long exp, long gold) {
        if (exp > 0) logs.add("获得灵力 +" + exp);
        if (gold > 0) logs.add("，金币 +" + gold);
    }

    public void addDefeatReward(long exp) {
        if (exp > 0) logs.add("惜败……你获得了 " + exp + " 灵力作为安慰");
    }

    public void addPveVictory(int rounds, long exp, long gold, long spiritStones, String itemName) {
        logs.add("---");
        logs.add("经过 " + rounds + " 回合激战，你成功击败了怪物！");
        logs.add("获得了 " + exp + " 点经验。");
        if (gold > 0) logs.add("获得了 " + gold + " 金币。");
        if (spiritStones > 0) logs.add("获得了 " + spiritStones + " 灵石。");
        if (itemName != null) logs.add("怪物身上掉落了一件物品！");
    }

    public void addPveDefeat(String monsterName, int hpLost) {
        logs.add("---");
        logs.add("你被【" + monsterName + "】击败了，损失了 " + hpLost + " 点生命值...");
    }

    public void addBossVictory(int rounds, String bossName) {
        logs.add("---");
        logs.add("🎉 经过 " + rounds + " 回合的鏖战，你成功击败了 Boss【" + bossName + "】！");
    }

    public void addBossDefeat(String bossName) {
        logs.add("---");
        logs.add("Boss【" + bossName + "】的力量太过强大，你被击败了...");
    }

    public void addTeamVictory(String bossName, String lastHitPlayerName) {
        logs.add("---");
        logs.add("🎉 团队成功击败了【" + bossName + "】！");
        logs.add("最终一击由【" + lastHitPlayerName + "】完成！");
    }

    public void addTeamDefeat() {
        logs.add("---");
        logs.add("💀 团队全军覆没，Boss战失败...");
    }

    public void addBossFormChange(String bossName, int debuffPercent) {
        logs.add("⚠【" + bossName + "】进入新阶段！属性提升，玩家削弱" + debuffPercent + "%！");
    }

    public void addPlayerDefeated(String playerName) {
        logs.add("【" + playerName + "】被击败！");
    }

    public void addTeamPlayerDamage(String bossName, String playerName, int damage, int currentHp) {
        logs.add("【" + bossName + "】攻击【" + playerName + "】，造成 " + damage + " 点伤害，剩余生命 " + Math.max(0, currentHp));
    }

    public void addTeamPlayerAttack(String playerName, String bossName, int damage) {
        logs.add("【" + playerName + "】对【" + bossName + "】造成 " + damage + " 点伤害");
    }

    public List<String> getLogs() {
        return new ArrayList<>(logs);
    }

    public void clear() {
        logs.clear();
    }

    public int size() {
        return logs.size();
    }
}