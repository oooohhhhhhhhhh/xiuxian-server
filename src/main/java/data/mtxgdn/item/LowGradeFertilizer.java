package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class LowGradeFertilizer extends Item {
    public LowGradeFertilizer() {
        super("mtxgdn", "low_grade_fertilizer", ItemType.MATERIAL, ItemRarity.COMMON,
            999, 20, true, 0, EmptyEffect.INSTANCE);
    }
}