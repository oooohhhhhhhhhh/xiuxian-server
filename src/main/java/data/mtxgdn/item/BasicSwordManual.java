package data.mtxgdn.item;

import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.LearnSkillEffect;

public class BasicSwordManual extends Item {
    public BasicSwordManual() {
        super("mtxgdn", "basic_sword_manual", ItemType.SKILL_BOOK, ItemRarity.COMMON,
            1, 100, true, 0, LearnSkillEffect.of(9));
    }
}
