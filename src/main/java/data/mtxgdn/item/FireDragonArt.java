package data.mtxgdn.item;

import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.LearnSkillEffect;

public class FireDragonArt extends Item {
    public FireDragonArt() {
        super("mtxgdn", "fire_dragon_art", ItemType.SKILL_BOOK, ItemRarity.EPIC,
            1, 5000, true, 3, LearnSkillEffect.of(3));
    }
}
