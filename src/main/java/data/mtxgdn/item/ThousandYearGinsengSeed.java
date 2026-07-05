package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class ThousandYearGinsengSeed extends Item {
    public ThousandYearGinsengSeed() {
        super("mtxgdn", "thousand_year_ginseng_seed", ItemType.SEED, ItemRarity.RARE,
            999, 30, true, 0, EmptyEffect.INSTANCE);
    }
}