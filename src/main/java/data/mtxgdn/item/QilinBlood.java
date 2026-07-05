package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class QilinBlood extends Item {
    public QilinBlood() {
        super("mtxgdn", "qilin_blood", ItemType.MATERIAL, ItemRarity.MYTHIC,
            10, 10000, true, 5, EmptyEffect.INSTANCE);
    }
}