package com.mtxgdn.game.command.collection;

import com.mtxgdn.TestBase;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.PlayerService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /图鉴} 的渲染测试：总览 / 分类 / 奖励三个入口都要有内容、不抛异常。
 */
class CollectionCommandRenderTest extends TestBase {

    private static PlayerInfo player;

    @BeforeAll
    static void createCharacter() {
        PlayerService playerService = ServiceRegistry.container().getPlayerService();
        long userId = DatabaseManager.insertRow("users", java.util.Map.of(
                "username", "collection_render_user",
                "password", org.mindrot.jbcrypt.BCrypt.hashpw(
                        "Password123!", org.mindrot.jbcrypt.BCrypt.gensalt())));
        playerService.createPlayer(userId, "图鉴渲染者");
        player = playerService.getPlayerByUserId(userId);
        assertNotNull(player, "测试角色创建失败");
    }

    private static final class CapturingContext extends CommandContext {
        private final PlayerInfo player;
        private final List<String> replies = new ArrayList<>();

        CapturingContext(PlayerInfo player, String arg) {
            super("collection-render-sender", "图鉴渲染者", arg);
            this.player = player;
        }

        @Override public boolean isGroup() { return false; }
        @Override public void reply(String message) { replies.add(message); }
        @Override public void replyPrivate(String message) { replies.add(message); }
        @Override public Long requireBinding() { return 1L; }
        @Override public PlayerInfo requirePlayer(Long userId) { return player; }

        String lastReply() {
            return replies.isEmpty() ? "" : replies.get(replies.size() - 1);
        }
    }

    private static String render(String arg) {
        CapturingContext ctx = new CapturingContext(player, arg);
        new CollectionCommand().execute(ctx);
        String out = ctx.lastReply();
        assertFalse(out.isBlank(), "指令没有任何回复: /图鉴 " + arg);
        return out;
    }

    @Test
    @DisplayName("无参数：总览显示各分类进度")
    void overview() {
        String out = render("");
        assertTrue(out.contains("全图鉴"), out);
        assertTrue(out.contains("物品"), out);
        assertTrue(out.contains("称号"), out);
        assertTrue(out.contains("总计"), out);
    }

    @Test
    @DisplayName("/图鉴 查看 物品：列出带状态的条目与分页")
    void categoryDetail() {
        String out = render("查看 物品");
        assertTrue(out.contains("物品图鉴"), out);
        assertTrue(out.contains("第 1/"), out);
        assertTrue(out.contains("✔") || out.contains("✘"), out);
    }

    @Test
    @DisplayName("/图鉴 物品：未知子命令也能当成分类名")
    void unknownSubAsCategory() {
        String out = render("物品");
        assertTrue(out.contains("物品图鉴"), out);
    }

    @Test
    @DisplayName("/图鉴 奖励：展示每条与集齐奖励")
    void rewards() {
        String out = render("奖励");
        assertTrue(out.contains("图鉴奖励"), out);
        assertTrue(out.contains("每条"), out);
        assertTrue(out.contains("集齐"), out);
    }
}
