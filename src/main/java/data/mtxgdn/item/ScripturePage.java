package data.mtxgdn.item;

import com.mtxgdn.game.item.ExpEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class ScripturePage extends Item {
    public ScripturePage() {
        super("mtxgdn", "scripture_page", ItemType.MATERIAL, ItemRarity.UNCOMMON,
            10, 50, true, 0, ExpEffect.of(200));
    }
}
