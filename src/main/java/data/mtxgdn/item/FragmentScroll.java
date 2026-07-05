package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class FragmentScroll extends Item {
    public FragmentScroll() {
        super("mtxgdn", "fragment_scroll", ItemType.QUEST, ItemRarity.UNCOMMON,
            9, 0, false, 1, EmptyEffect.INSTANCE);
    }
}