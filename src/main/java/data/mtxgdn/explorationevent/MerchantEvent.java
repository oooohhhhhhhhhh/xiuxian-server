package data.mtxgdn.explorationevent;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

import java.util.List;
import java.util.Random;

public class MerchantEvent extends ExplorationEvent {
    public MerchantEvent() {
        super("mtxgdn", "merchant", 10);
    }

    @Override
    public void execute(Player player, PlayerService playerService,
                         ItemService itemService, Random random,
                         ExplorationResult result, List<String> log) {
        result.setEventType("merchant");
        result.setEventDescription("偶遇游商");

        double roll = random.nextDouble();
        if (roll < 0.3) {
            int stoneCount = random.nextInt(1, 4);
            itemService.addItem(player.getId(), "mtxgdn:enhance_stone", stoneCount);
            result.setItemGained("mtxgdn:enhance_stone");
            result.setItemQuantity(stoneCount);
            log.add("🧳 一位背着大包袱的游商从远处走来，他向你展示了珍藏的强化石。");
            log.add("你以公道价格买下了 " + stoneCount + " 颗强化石。");
            result.setMessage("从游商处购得 " + stoneCount + " 颗强化石");
        } else if (roll < 0.6) {
            String[] items = {"mtxgdn:healing_pill", "mtxgdn:mana_pill", "mtxgdn:spirit_spring_water", "mtxgdn:beast_core"};
            String giftItem = items[random.nextInt(items.length)];
            int qty = random.nextInt(1, 3);
            itemService.addItem(player.getId(), giftItem, qty);
            result.setItemGained(giftItem);
            result.setItemQuantity(qty);
            log.add("🧳 游商笑呵呵地掏出一些稀罕物件，非要送给你作为见面礼。");
            result.setMessage("游商赠送了一些物品");
        } else if (roll < 0.85) {
            int price = (player.getRealm() + 1) * 200 + random.nextInt(500);
            playerService.addGold(player.getId(), price);
            result.setGoldGained(price);
            log.add("🧳 你帮游商指了个路，他高兴地给了你 " + price + " 金币作为报酬。");
            result.setMessage("为游商指路，获得 " + price + " 金币");
        } else {
            long spiritStones = (player.getRealm() + 1) * 40L;
            itemService.addSpiritStones(player.getId(), spiritStones);
            result.setSpiritStonesGained(spiritStones);
            log.add("🧳 游商拿出几块闪闪发光的灵石，说要和你交换一些情报。");
            log.add("你把最近的见闻说给他听，获得了 " + spiritStones + " 灵石。");
            result.setMessage("与游商交换情报，获得 " + spiritStones + " 灵石");
        }
    }
}
