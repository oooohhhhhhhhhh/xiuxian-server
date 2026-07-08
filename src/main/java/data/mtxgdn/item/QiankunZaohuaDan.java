package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class QiankunZaohuaDan extends Item {
    public QiankunZaohuaDan() {
        super("mtxgdn", "qiankun_zaohua_dan", ItemType.CONSUMABLE, ItemRarity.LEGENDARY,
            99, 0, false, 0, EmptyEffect.INSTANCE);
    }
}
