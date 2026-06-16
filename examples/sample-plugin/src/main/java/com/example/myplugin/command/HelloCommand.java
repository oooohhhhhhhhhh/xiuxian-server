package com.example.myplugin.command;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;

/**
 * 示例命令：用户在聊天中输入 /hello 或 /你好，
 * 即可看到一句问候并获得一些灵石奖励。
 */
public class HelloCommand extends Command {

    public HelloCommand() {
        super(
            new String[]{"hello", "你好"},  // 命令别名
            "向修仙者问好",                    // 描述
            "/你好",                          // 用法
            "经济"                             // 分类（与服务端现有分类保持一致）
        );
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        // 给玩家 100 灵石作为奖励（这只是演示，可在自己插件中写任意逻辑）
        long playerId = p.getId();
        var economy = ServiceRegistry.getEconomyService();
        var itemService = ServiceRegistry.getItemService();
        itemService.addSpiritStones(playerId, 100);

        ctx.reply("🌸 道友安好！欢迎来到修仙世界。\n" +
                "（示例插件赠送你 100 灵石作为见面礼）");
    }
}
