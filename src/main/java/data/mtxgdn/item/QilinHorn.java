package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class QilinHorn extends Item {
    public QilinHorn() {
        super("mtxgdn", "qilin_horn", ItemType.MATERIAL, ItemRarity.LEGENDARY,
            999, 600, true, 0, EmptyEffect.INSTANCE);
    }
}
