package data.mtxgdn.item;

import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.LearnSkillEffect;

public class ThunderBoltTalisman extends Item {
    public ThunderBoltTalisman() {
        super("mtxgdn", "thunder_bolt_talisman", ItemType.SKILL_BOOK, ItemRarity.LEGENDARY,
            1, 20000, true, 5, LearnSkillEffect.of(4));
    }
}
