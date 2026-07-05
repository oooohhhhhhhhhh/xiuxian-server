package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class TianshanSnowLotusSeed extends Item {
    public TianshanSnowLotusSeed() {
        super("mtxgdn", "tianshan_snow_lotus_seed", ItemType.SEED, ItemRarity.LEGENDARY,
            999, 200, true, 0, EmptyEffect.INSTANCE);
    }
}
