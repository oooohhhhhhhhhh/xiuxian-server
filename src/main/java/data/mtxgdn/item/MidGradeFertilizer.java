package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class MidGradeFertilizer extends Item {
    public MidGradeFertilizer() {
        super("mtxgdn", "mid_grade_fertilizer", ItemType.MATERIAL, ItemRarity.UNCOMMON,
            999, 50, true, 0, EmptyEffect.INSTANCE);
    }
}