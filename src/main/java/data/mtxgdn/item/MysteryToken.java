package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class MysteryToken extends Item {
    public MysteryToken() {
        super("mtxgdn", "mystery_token", ItemType.QUEST, ItemRarity.RARE,
            1, 0, false, 2, EmptyEffect.INSTANCE);
    }
}