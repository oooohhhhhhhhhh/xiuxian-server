package com.example.myplugin.item;

import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

/**
 * 示例物品。使用后向玩家说一句话。
 * 注意：物品的命名空间（第一个参数）建议使用插件的名字，
 * 以避免与其它插件冲突。
 */
public class DemoItem extends Item {

    public DemoItem() {
        super(
            "示例插件",   // 命名空间（用于避免与其它插件/服务端自带物品冲突）
            "demo_talisman",          // 物品唯一 key
            ItemType.CONSUMABLE,      // 物品类型
            ItemRarity.RARE,          // 稀有度
            10,                       // 最大堆叠数
            888,                      // 价格
            true,                     // 是否可交易
            1,                        // 所需境界
            EmptyEffect.INSTANCE      // 物品效果（可用自定义效果）
        );
    }

    @Override
    public String use(long playerId) {
        return "✨ 你使用了示例物品，顿感神清气爽！";
    }
}
