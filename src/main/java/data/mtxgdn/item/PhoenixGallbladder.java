package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class PhoenixGallbladder extends Item {
    public PhoenixGallbladder() {
        super("mtxgdn", "phoenix_gallbladder", ItemType.MATERIAL, ItemRarity.LEGENDARY,
            999, 800, true, 0, EmptyEffect.INSTANCE);
    }
}
