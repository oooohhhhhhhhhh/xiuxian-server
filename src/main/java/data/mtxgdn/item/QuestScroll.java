package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class QuestScroll extends Item {
    public QuestScroll() {
        super("mtxgdn", "quest_scroll", ItemType.QUEST, ItemRarity.EPIC,
            1, 0, false, 3, EmptyEffect.INSTANCE);
    }
}