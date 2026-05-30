package data.mtxgdn.explorationevent;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;
import java.util.Random;

public class MonsterEvent extends ExplorationEvent {
    public MonsterEvent() {
        super("mtxgdn", "monster", 25);
    }

    @Override
    public void execute(Player player, PlayerService playerService,
                         ItemService itemService, Random random,
                         ExplorationResult result, List<String> log) {
        result.setEventType("monster");
        result.setEventDescription("遭遇妖兽");

        int realm = player.getRealm() + 1;
        String monsterName = generateMonsterName(random);
        int monsterAtk = 5 + realm * 15 + random.nextInt(realm * 10);
        int monsterHp = 30 + realm * 40 + random.nextInt(realm * 30);

        log.add("⚔ 前方突然窜出一只【" + monsterName + "】！");
        log.add("妖兽属性 - 攻击:" + monsterAtk + " 生命:" + monsterHp);

        int playerAtk = player.getAttack() + player.getSpirit();
        int playerHp = player.getHp();
        boolean playerFirst = player.getSpeed() >= 5 + realm * 3;
        int rounds = 0;
        boolean won = false;

        while (playerHp > 0 && monsterHp > 0 && rounds < 30) {
            rounds++;
            if (playerFirst) {
                int dmg = Math.max(1, playerAtk + random.nextInt(-5, 6));
                monsterHp -= dmg;
                if (monsterHp <= 0) { won = true; break; }
                dmg = Math.max(1, monsterAtk - player.getDefense() / 2 + random.nextInt(-3, 4));
                playerHp -= dmg;
            } else {
                int dmg = Math.max(1, monsterAtk - player.getDefense() / 2 + random.nextInt(-3, 4));
                playerHp -= dmg;
                if (playerHp <= 0) break;
                dmg = Math.max(1, playerAtk + random.nextInt(-5, 6));
                monsterHp -= dmg;
                if (monsterHp <= 0) { won = true; break; }
            }
        }

        int hpLost = player.getHp() - Math.max(0, playerHp);
        result.setHpLost(hpLost);
        result.setMonsterName(monsterName);
        result.setMonsterDefeated(won);

        if (won) {
            long exp = realm * 80L + random.nextLong(realm * 30L);
            playerService.addExperience(player.getId(), exp);
            result.setExpGained(exp);

            log.add("经过 " + rounds + " 回合激战，你成功击败了【" + monsterName + "】！");
            log.add("获得了 " + exp + " 点经验。");

            if (random.nextDouble() < 0.3) {
                String[] loot = {"spirit_grass", "iron_ore", "healing_pill", "mana_pill", "spirit_recovery_pill"};
                String lootItem = loot[random.nextInt(loot.length)];
                if (ItemRegistry.contains(lootItem)) {
                    itemService.addItem(player.getId(), lootItem, 1);
                    result.setItemGained(lootItem);
                    result.setItemQuantity(1);
                    log.add("妖兽身上掉落了一件物品！");
                }
            }
            result.setMessage("击败了【" + monsterName + "】，获得 " + exp + " 点经验");
        } else {
            log.add("你被【" + monsterName + "】击败了，损失了 " + hpLost + " 点生命值...");
            result.setMessage("被【" + monsterName + "】击败，损失了 " + hpLost + " 点生命值");
        }

        int finalHp = Math.max(0, playerHp);
        try (var conn = com.mtxgdn.db.DatabaseManager.getConnection();
             var ps = conn.prepareStatement("UPDATE players SET hp = ? WHERE id = ?")) {
            ps.setInt(1, finalHp);
            ps.setLong(2, player.getId());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("更新生命值失败", e);
        }
    }

    private static String generateMonsterName(Random random) {
        String[] prefixes = {"烈焰", "冰霜", "暗影", "血牙", "铁甲", "疾风", "毒雾", "岩石", "幽冥", "金翅"};
        String[] names = {"妖狼", "巨蟒", "魔蛛", "赤虎", "黑熊", "妖鹰", "石魔", "蛇妖", "蝎王", "魅狐"};
        return prefixes[random.nextInt(prefixes.length)] + names[random.nextInt(names.length)];
    }
}
