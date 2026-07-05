package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class QuestLetter extends Item {
    public QuestLetter() {
        super("mtxgdn", "quest_letter", ItemType.QUEST, ItemRarity.COMMON,
            1, 0, false, 0, EmptyEffect.INSTANCE);
    }
}