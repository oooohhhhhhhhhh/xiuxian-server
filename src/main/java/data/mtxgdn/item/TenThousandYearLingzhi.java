package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class TenThousandYearLingzhi extends Item {
    public TenThousandYearLingzhi() {
        super("mtxgdn", "ten_thousand_year_lingzhi", ItemType.MATERIAL, ItemRarity.EPIC,
            999, 150, true, 0, EmptyEffect.INSTANCE);
    }
}