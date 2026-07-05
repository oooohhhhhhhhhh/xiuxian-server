package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class PhoenixFeather extends Item {
    public PhoenixFeather() {
        super("mtxgdn", "phoenix_feather", ItemType.MATERIAL, ItemRarity.LEGENDARY,
            20, 2000, true, 4, EmptyEffect.INSTANCE);
    }
}