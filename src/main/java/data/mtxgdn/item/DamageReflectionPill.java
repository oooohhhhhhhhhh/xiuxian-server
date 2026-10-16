package data.mtxgdn.item;

import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ReflectionBuffEffect;

public class DamageReflectionPill extends Item {
    public DamageReflectionPill() {
        // 伤害反弹 30%（120 秒），战斗中真实反弹伤害
        super("mtxgdn", "damage_reflection_pill", ItemType.CONSUMABLE, ItemRarity.UNCOMMON,
            20, 120, true, 1, ReflectionBuffEffect.of(30, 120));
    }
}