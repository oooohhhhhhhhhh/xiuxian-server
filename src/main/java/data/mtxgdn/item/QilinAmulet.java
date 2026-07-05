package data.mtxgdn.item;

import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class QilinAmulet extends Item {
    public QilinAmulet() {
        super("mtxgdn", "qilin_amulet", ItemType.TREASURE, ItemRarity.MYTHIC,
            1, 200000, true, 5, BuffEffect.of(150, 200, 100, 200, 0));
    }
}