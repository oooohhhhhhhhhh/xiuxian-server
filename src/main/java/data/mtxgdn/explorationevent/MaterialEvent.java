package data.mtxgdn.explorationevent;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;
import java.util.Random;

public class MaterialEvent extends ExplorationEvent {
    public MaterialEvent() {
        super("mtxgdn", "material", 10);
    }

    @Override
    public void execute(Player player, PlayerService playerService,
                         ItemService itemService, Random random,
                         ExplorationResult result, List<String> log) {
        result.setEventType("material");
        result.setEventDescription("采集材料");

        String[][] materials = {
            {"spirit_grass", "灵草", "普通"},
            {"iron_ore", "铁矿石", "普通"},
            {"silver_ore", "银矿石", "普通"},
            {"gold_ore", "金矿石", "稀有"},
            {"mythril_ore", "秘银矿石", "稀有"},
            {"beast_core", "兽核", "普通"},
            {"demon_core", "魔核", "稀有"},
            {"dragon_blood_crystal", "龙血晶", "史诗"},
            {"ice_crystal", "冰晶", "稀有"},
            {"heaven_fire_stone", "天火石", "史诗"},
            {"star_sand", "星辰沙", "稀有"},
            {"spirit_pearl", "灵珠", "史诗"},
            {"thousand_year_heshouwu", "千年何首乌", "稀有"},
            {"deep_sea_coral", "深海珊瑚", "史诗"},
            {"ghost_fire_essence", "鬼火精华", "稀有"},
            {"sky_silk", "天蚕丝", "史诗"},
            {"blood_jade_marrow", "血玉髓", "史诗"},
        };

        double rarityRoll = random.nextDouble();
        int index;
        if (rarityRoll < 0.5) {
            index = random.nextInt(6);
        } else if (rarityRoll < 0.85) {
            index = 6 + random.nextInt(5);
        } else {
            index = 11 + random.nextInt(6);
        }

        String matKey = materials[index][0];
        String matName = materials[index][1];
        int quantity = random.nextInt(1, 4);

        if (ItemRegistry.contains(matKey)) {
            itemService.addItem(player.getId(), matKey, quantity);
            result.setItemGained(matKey);
            result.setItemQuantity(quantity);
            log.add("💎 你在一处隐秘的角落发现了珍贵的炼丹材料！");
            log.add("获得了【" + matName + "】x" + quantity);
            result.setMessage("采集到【" + matName + "】x" + quantity);
        } else {
            long exp = (player.getRealm() + 1) * 25L;
            playerService.addExperience(player.getId(), exp);
            result.setExpGained(exp);
            log.add("💎 你发现了一些奇怪的矿石和草药，先收起来再说。");
            log.add("获得了 " + exp + " 点经验。");
            result.setMessage("采集到一些材料，获得 " + exp + " 点经验");
        }
    }
}
