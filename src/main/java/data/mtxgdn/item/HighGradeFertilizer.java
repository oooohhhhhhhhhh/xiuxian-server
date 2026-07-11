package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class HighGradeFertilizer extends Item {
    public HighGradeFertilizer() {
        super("mtxgdn", "high_grade_fertilizer", ItemType.MATERIAL, ItemRarity.RARE,
            999, 100, true, 0, EmptyEffect.INSTANCE);
    }
}