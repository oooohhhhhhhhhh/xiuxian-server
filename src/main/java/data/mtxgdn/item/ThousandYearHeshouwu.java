package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class ThousandYearHeshouwu extends Item {
    public ThousandYearHeshouwu() {
        super("mtxgdn", "thousand_year_heshouwu", ItemType.MATERIAL, ItemRarity.RARE,
            999, 80, true, 0, EmptyEffect.INSTANCE);
    }
}
