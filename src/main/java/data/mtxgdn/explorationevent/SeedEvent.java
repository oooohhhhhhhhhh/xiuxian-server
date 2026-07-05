package data.mtxgdn.explorationevent;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;
import java.util.Random;

public class SeedEvent extends ExplorationEvent {
    public SeedEvent() {
        super("mtxgdn", "seed", 12);
    }

    @Override
    public void execute(Player player, PlayerService playerService,
                         ItemService itemService, Random random,
                         ExplorationResult result, List<String> log) {
        result.setEventType("seed");
        result.setEventDescription("发现种子");

        String[][] seeds = {
            {"spirit_grass_seed", "灵草种子"},
            {"dark_ice_grass_seed", "暗冰草种子"},
            {"fire_vine_seed", "火焰藤种子"},
            {"thousand_year_ginseng_seed", "千年人参种子"},
            {"nether_flower_seed", "幽冥花种子"},
            {"star_grass_seed", "星辰草种子"},
            {"blood_lingzhi_seed", "血灵芝种子"},
            {"tianshan_snow_lotus_seed", "天山雪莲种子"},
        };

        double rarityRoll = random.nextDouble();
        int index;
        if (rarityRoll < 0.5) {
            index = random.nextInt(4);
        } else if (rarityRoll < 0.85) {
            index = 4 + random.nextInt(3);
        } else {
            index = 7;
        }

        String seedKey = seeds[index][0];
        String seedName = seeds[index][1];
        int quantity = random.nextInt(1, 3);

        if (ItemRegistry.contains(seedKey)) {
            itemService.addItem(player.getId(), seedKey, quantity);
            result.setItemGained(seedKey);
            result.setItemQuantity(quantity);
            log.add("🌱 你在草丛中发现了几颗珍稀的种子！");
            log.add("获得了【" + seedName + "】x" + quantity);
            result.setMessage("发现种子！获得【" + seedName + "】x" + quantity);
        } else {
            long exp = (player.getRealm() + 1) * 20L;
            playerService.addExperience(player.getId(), exp);
            result.setExpGained(exp);
            log.add("🌱 你发现了一些奇异的植物种子，虽然不认识，但也许有用。");
            log.add("获得了 " + exp + " 点经验。");
            result.setMessage("发现未知种子，获得 " + exp + " 点经验");
        }
    }
}
