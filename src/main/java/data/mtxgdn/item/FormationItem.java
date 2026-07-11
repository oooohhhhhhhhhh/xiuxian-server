package data.mtxgdn.item;

import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.EmptyEffect;

public class FormationItem extends Item {

    public FormationItem(String key, ItemRarity rarity, int level) {
        super("mtxgdn", key, ItemType.TREASURE, rarity, 1, getPrice(rarity, level), true, level, EmptyEffect.INSTANCE);
    }

    private static int getPrice(ItemRarity rarity, int level) {
        int base = switch (rarity) {
            case COMMON -> 50;
            case UNCOMMON -> 150;
            case RARE -> 300;
            case EPIC -> 800;
            case LEGENDARY -> 2000;
            case MYTHIC -> 5000;
        };
        return base * level;
    }
}