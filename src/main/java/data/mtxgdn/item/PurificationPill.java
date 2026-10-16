package data.mtxgdn.item;

import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.PurificationEffect;

public class PurificationPill extends Item {
    public PurificationPill() {
        // 清除重伤状态并恢复生命（真实效果）
        super("mtxgdn", "purification_pill", ItemType.CONSUMABLE, ItemRarity.UNCOMMON,
            30, 80, true, 1, PurificationEffect.of());
    }
}