package data.mtxgdn.item;

import com.mtxgdn.game.item.CurrencyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SpiritStonePouch extends Item {
    public SpiritStonePouch() {
        // 5 下品灵石等价回收：防止"聚灵石配方"5进50出 的灵石印钞漏洞
        super("mtxgdn", "spirit_stone_pouch", ItemType.CONSUMABLE, ItemRarity.COMMON,
            999, 0, false, 0, CurrencyEffect.of(0, 5, 0));
    }
}