package com.mtxgdn.onebot.command.map;

import com.mtxgdn.TestBase;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.config.MapDefaults;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.PlayerService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code /地图 list} 的渲染回归测试。
 *
 * <p>背景：这里的三个毛病都不会报错，只会"慢慢失真"——</p>
 * <ul>
 *   <li>世界脉络原是一段硬编码 ASCII 图，只画到 (10)问道峰，后 5 个地点与 8 条连接
 *       从不在图上，地图数据改了图也不会变；</li>
 *   <li>区域标题按"区域名一变就打一次"输出，而地点 id 与区域并不连续
 *       （南荒是 8、12；中原是 4~6、13~14；西域是 7、15），同一区域出现多个标题；</li>
 *   <li>境界门槛把境界 id 当层数拼，问道峰显示成"炼气+7"。</li>
 * </ul>
 */
class MapCommandRenderTest extends TestBase {

    private static PlayerInfo player;

    @BeforeAll
    static void createCharacter() {
        PlayerService playerService = new PlayerService();
        long userId = DatabaseManager.insertRow("users", java.util.Map.of(
                "username", "map_render_user",
                "password", org.mindrot.jbcrypt.BCrypt.hashpw(
                        "Password123!", org.mindrot.jbcrypt.BCrypt.gensalt())));
        playerService.createPlayer(userId, "看图的道友");
        player = playerService.getPlayerByUserId(userId);
        assertNotNull(player, "测试角色创建失败");
    }

    /** 捕获指令回复的最小上下文（不依赖任何适配器）。 */
    private static final class CapturingContext extends CommandContext {
        private final PlayerInfo player;
        private final List<String> replies = new ArrayList<>();

        CapturingContext(PlayerInfo player) {
            super("map-render-sender", "看图的道友", "list");
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

    private static String renderList() {
        CapturingContext ctx = new CapturingContext(player);
        new MapCommand().execute(ctx);
        String out = ctx.lastReply();
        assertFalse(out.isBlank(), "指令没有任何回复");
        return out;
    }

    @Test
    @DisplayName("区域标题不得重复（地点 id 与区域并不连续）")
    void testRegionHeadersAreNotDuplicated() {
        String out = renderList();

        for (MapDefaults.LocationSeed seed : MapDefaults.LOCATIONS) {
            String header = "▍" + seed.region() + "\n";
            int count = out.split(Pattern.quote(header), -1).length - 1;
            assertEquals(1, count,
                    "区域【" + seed.region() + "】标题出现 " + count + " 次，应为 1 次");
        }
    }

    @Test
    @DisplayName("世界脉络必须覆盖全部出厂地点（此前硬编码图漏了后 5 个）")
    void testWorldTreeCoversAllLocations() {
        String out = renderList();

        for (MapDefaults.LocationSeed seed : MapDefaults.LOCATIONS) {
            assertTrue(out.contains("(" + seed.id() + ")" + seed.name()),
                    "世界脉络缺少地点 (" + seed.id() + ")" + seed.name());
        }
    }

    @Test
    @DisplayName("境界门槛显示境界名，而不是把境界 id 当层数拼")
    void testRealmGateShowsRealmName() {
        String out = renderList();

        // 问道峰（id=10）门槛是大乘期（realm 7）
        assertTrue(out.contains("(10)问道峰"), "列表应包含问道峰");
        assertTrue(out.contains("大乘期"), "问道峰门槛应显示为「大乘期」");
        assertFalse(out.contains("炼气+"), "不得再出现把境界 id 当层数拼的旧文案（如「炼气+7」）");
    }

    @Test
    @DisplayName("每个地点都带灵气倍率与安全区标记")
    void testBonusesAndSafeZoneRendered() {
        String out = renderList();

        assertTrue(out.contains("灵气×"), "列表应显示灵气倍率");
        // 问道峰灵气 1.50 / 青云村 1.00
        assertTrue(out.contains("1.5"), "应显示问道峰的灵气倍率");
        assertTrue(out.contains("🛡"), "安全区应有标记");
    }
}
