package data.mtxgdn.explorationevent;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;
import java.util.Random;

public class SpiritSpringEvent extends ExplorationEvent {
    public SpiritSpringEvent() {
        super("mtxgdn", "spirit_spring", 8);
    }

    @Override
    public void execute(Player player, PlayerService playerService,
                         ItemService itemService, Random random,
                         ExplorationResult result, List<String> log) {
        result.setEventType("spirit_spring");
        result.setEventDescription("灵泉沐浴");

        int healHp = player.getMaxHp() / 4 + random.nextInt(player.getMaxHp() / 4);
        int healMp = player.getMaxMp() / 4 + random.nextInt(player.getMaxMp() / 4);
        long exp = (player.getRealm() + 1) * 80L;

        playerService.addExperience(player.getId(), exp);
        itemService.addItem(player.getId(), "mtxgdn:spirit_spring_water", random.nextInt(1, 4));
        result.setExpGained(exp);

        log.add("💧 你发现了一处隐秘的灵泉！泉水清澈见底，散发着浓郁的灵气。");
        log.add("你在灵泉中沐浴修炼，伤势恢复，灵力充盈...");
        log.add("HP 恢复 +" + healHp + "，MP 恢复 +" + healMp);
        log.add("获得了 " + exp + " 点经验和一些灵泉水。");

        try (var conn = com.mtxgdn.db.DatabaseManager.getConnection();
             var ps = conn.prepareStatement("UPDATE players SET hp = LEAST(max_hp, hp + ?), mp = LEAST(max_mp, mp + ?) WHERE id = ?")) {
            ps.setInt(1, healHp);
            ps.setInt(2, healMp);
            ps.setLong(3, player.getId());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("恢复生命法力失败", e);
        }

        result.setMessage("灵泉沐浴，恢复了生命和法力");
    }
}
