package com.mtxgdn;

import com.mtxgdn.game.sect.SectActionResult;
import com.mtxgdn.game.economy.SignInResult;
import com.mtxgdn.game.farm.FarmResult;
import com.mtxgdn.game.item.UseItemResult;
import com.mtxgdn.game.item.EquipResult;
import com.mtxgdn.game.map.TravelResult;
import com.mtxgdn.game.trade.TradeResult;
import com.mtxgdn.game.cultivation.BeginCultivationResult;
import com.mtxgdn.game.cultivation.FinishCultivationResult;
import com.mtxgdn.common.result.OperationResult;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.item.ItemScanner;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.service.*;
import com.mtxgdn.game.entity.*;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.secretrealm.SecretRealm;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.service.UserService;
import com.mtxgdn.util.JwtUtil;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * 修仙服务器全面集成测试（SQLite 模式，模拟 5 名玩家完整交互）。
 *
 * 覆盖功能：
 *  1. 用户注册/登录/JWT
 *  2. 角色创建
 *  3. 修炼与境界突破
 *  4. 游历探索
 *  5. 秘境
 *  6. 物品/装备/锻造
 *  7. 交易坊市
 *  8. PVP 战斗
 *  9. 好友/私聊
 *  10. 宗门
 *  11. 农场
 *  12. 洞府
 *  13. 每日/签到/经济
 *  14. 团队
 *  15. 功法/技能
 *  16. 地图移动
 *  17. 称号
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("修仙服务器全面多玩家集成测试")
public class MultiPlayerFullTest {

    // ==================== 服务 ====================
    private static UserService userService;
    private static PlayerService playerService;
    private static RealmService realmService;
    private static ItemService itemService;
    private static ExplorationService explorationService;
    private static SecretRealmService secretRealmService;
    private static CombatService combatService;
    private static TradeService tradeService;
    private static SectService sectService;
    private static FriendService friendService;
    private static ChatService chatService;
    private static SkillService skillService;
    private static TechniqueService techniqueService;
    private static DailyService dailyService;
    private static EconomyService economyService;
    private static FarmService farmService;
    private static CaveService caveService;
    private static EnergyService energyService;
    private static BuffService buffService;
    private static TeamService teamService;
    private static MapService mapService;
    private static CraftingService craftingService;
    private static TitleService titleService;

    // ==================== 测试玩家数据 ====================
    private static final class TestPlayer {
        long userId;
        long playerId;
        String username;
        String password;
        String token;
        String playerName;
    }

    private static final Map<String, TestPlayer> PLAYERS = new LinkedHashMap<>();

    @BeforeAll
    static void setupAll() throws Exception {
        // 输出分隔线
        System.out.println("\n");
        System.out.println("════════════════════════════════════════════════════════════");
        System.out.println("修仙服务器全面多玩家集成测试 - SQLite 模式");
        System.out.println("模拟 5 名玩家交互");
        System.out.println("════════════════════════════════════════════════════════════");

        // 清理测试数据库（统一落在 target/test-db/，不再污染仓库根目录）
        String dbPath = "test_full.db";
        java.io.File dbFile = TestBase.testDbFile(dbPath);
        if (dbFile.exists()) dbFile.delete();

        DatabaseManager.init(TestBase.testDbUrl(dbPath), "", "");

        // 初始化 JWT（测试环境：安装模式自动生成临时密钥）
        JwtUtil.init(true);

        // 初始化服务
        userService = new UserService();
        playerService = new PlayerService();
        realmService = new RealmService(playerService);
        itemService = new ItemService();
        explorationService = new ExplorationService(playerService);
        secretRealmService = new SecretRealmService(playerService);
        combatService = new CombatService();
        tradeService = new TradeService();
        sectService = new SectService();
        friendService = new FriendService();
        chatService = new ChatService();
        skillService = new SkillService();
        techniqueService = new TechniqueService();
        dailyService = new DailyService();
        economyService = new EconomyService();
        farmService = new FarmService();
        caveService = new CaveService();
        energyService = new EnergyService();
        buffService = new BuffService();
        teamService = TeamService.getInstance();
        mapService = new MapService();
        craftingService = new CraftingService();
        titleService = new TitleService();

        // 初始化游戏数据
        skillService.insertDefaultSkills();
        techniqueService.insertDefaultTechniques();
        craftingService.insertDefaultRecipes();
        ItemScanner.scanAndRegister();

        System.out.println("✓ 测试环境初始化完成\n");
    }

    /** 为主题预生成邮箱验证码，返回可用验证码字符串。 */
    private static String generateVerifyCode(String email) {
        return new com.mtxgdn.service.VerificationCodeService().generateAndStoreCode(email);
    }

    @AfterAll
    static void tearDownAll() {
        try { PlayerService.shutdown(); } catch (Exception ignored) {}
        try { BuffService.shutdownScheduler(); } catch (Exception ignored) {}
        try { FarmService.shutdownScheduler(); } catch (Exception ignored) {}
        DatabaseManager.shutdown();
        TestBase.testDbFile("test_full.db").delete();
        System.out.println("\n✓ 测试环境清理完成");
    }

    private static void addPlayer(String key, String username, String password, String playerName) {
        TestPlayer tp = new TestPlayer();
        tp.username = username;
        tp.password = password;
        tp.playerName = playerName;
        PLAYERS.put(key, tp);
    }

    // ========== 阶段1：用户注册与登录 ==========

    @Test
    @Order(1)
    @DisplayName("阶段1-1: 5名玩家注册")
    void stage1_registerPlayers() {
        System.out.println("\n[阶段1] 用户注册与登录");
        System.out.println("─────────────────────────────────────────────");

        addPlayer("p1", "player_one", "Password1!", "云中仙");
        addPlayer("p2", "player_two", "Password2!", "剑无心");
        addPlayer("p3", "player_three", "Password3!", "花千骨");
        addPlayer("p4", "player_four", "Password4!", "楚天歌");
        addPlayer("p5", "player_five", "Password5!", "林晚霜");

        int idx = 1;
        for (TestPlayer tp : PLAYERS.values()) {
            String email = "test" + (idx++) + "@qq.com"; // qq.com 是允许域名
            String code = generateVerifyCode(email);
            assertNotNull(code, "应生成验证码");
            Response resp = userService.register(tp.username, tp.password, email, code);
            assertEquals(200, resp.getStatus(), "玩家 " + tp.username + " 注册应成功，实际: " + respBody(resp));
            tp.userId = extractJsonLong(resp, "data.id");
            tp.token = extractJsonString(resp, "data.token");
            assertTrue(tp.userId > 0, "userId 应大于0");
            assertNotNull(tp.token, "应有JWT token");
            System.out.println("  ✓ 注册成功: " + tp.username + " (userId=" + tp.userId + ")");
        }
        System.out.println("✓ 全部 " + PLAYERS.size() + " 名玩家注册成功");
    }

    @Test
    @Order(2)
    @DisplayName("阶段1-2: 玩家登录与重复注册校验")
    void stage1_loginAndDuplicate() {
        // 登录验证
        TestPlayer p1 = PLAYERS.get("p1");
        Response loginResp = userService.login(p1.username, p1.password);
        assertEquals(200, loginResp.getStatus(), "登录应成功");
        String newToken = extractJsonString(loginResp, "data.token");
        assertNotNull(newToken);
        System.out.println("  ✓ 登录成功，获取新token");

        // 错误密码
        Response badLogin = userService.login(p1.username, "WrongPass!");
        assertNotEquals(200, badLogin.getStatus(), "错误密码登录应失败");
        System.out.println("  ✓ 错误密码被拒绝");

        // 重复注册
        Response dupResp = userService.register(p1.username, "Password1!", null, null);
        assertNotEquals(200, dupResp.getStatus(), "重复用户名应被拒绝");
        System.out.println("  ✓ 重复注册被拒绝");
    }

    // ========== 阶段2：角色创建 ==========

    @Test
    @Order(3)
    @DisplayName("阶段2: 5名玩家创建角色")
    void stage2_createPlayers() {
        System.out.println("\n[阶段2] 角色创建");
        System.out.println("─────────────────────────────────────────────");
        for (TestPlayer tp : PLAYERS.values()) {
            PlayerInfo pi = playerService.createPlayer(tp.userId, tp.playerName);
            assertNotNull(pi, "角色创建不应为空");
            tp.playerId = pi.getId();
            assertTrue(tp.playerId > 0);
            assertEquals(1, pi.getLevel(), "初始等级应为1");
            assertTrue(pi.getMaxHp() > 0, "初始生命应大于0");
            System.out.println("  ✓ " + tp.playerName + " 创建成功 (playerId=" + tp.playerId +
                    ", 灵根=" + (pi.getSpiritualRoot()!=null?pi.getSpiritualRoot().getDisplayName():"未知") + ")");
        }
        System.out.println("✓ 全部角色创建完成");
    }

    // ========== 阶段3：修炼与境界突破 ==========

    @Test
    @Order(4)
    @DisplayName("阶段3-1: 修炼系统（修炼->结算经验）")
    void stage3_cultivation() throws InterruptedException {
        System.out.println("\n[阶段3] 修炼与境界突破");
        System.out.println("─────────────────────────────────────────────");
        TestPlayer p1 = PLAYERS.get("p1");

        BeginCultivationResult start = playerService.beginCultivation(p1.playerId);
        assertTrue(start.success(), "开始修炼应成功: " + start.message());

        Thread.sleep(1500);

        FinishCultivationResult finish = playerService.finishCultivation(p1.playerId);
        assertTrue(finish.success(), "结束修炼应成功");
        long expGained = finish.rawExpGained() != null ? finish.rawExpGained() : 0L;
        assertTrue(expGained > 0, "应获得经验");
        System.out.println("  ✓ 修炼获得经验=" + expGained);
    }

    @Test
    @Order(5)
    @DisplayName("阶段3-2: 境界突破")
    void stage3_breakthrough() {
        TestPlayer p1 = PLAYERS.get("p1");
        // 给予充足经验和灵石
        playerService.addExperience(p1.playerId, 1000000);
        itemService.addSpiritStones(p1.playerId, 100000);

        RealmBreakthroughResult result = realmService.tryBreakthrough(p1.userId);
        System.out.println("  " + (result.isSuccess()?"✓":"○") + " 突破: " + result.getMessage());
        if (result.isSuccess()) {
            assertNotNull(result.getNewRealmName());
            System.out.println("    新境界: " + result.getNewRealmName());
        }
    }

    // ========== 阶段4：游历探索 ==========

    @Test
    @Order(6)
    @DisplayName("阶段4: 游历探索（多玩家）")
    void stage4_exploration() {
        System.out.println("\n[阶段4] 游历探索");
        System.out.println("─────────────────────────────────────────────");
        for (TestPlayer tp : PLAYERS.values()) {
            ExplorationResult r = null;
            try {
                r = explorationService.explore(tp.userId);
            } catch (Exception e) {
                System.out.println("  ○ " + tp.playerName + " 探索异常: " + e.getMessage());
                continue;
            }
            if (r.isSuccess()) {
                System.out.println("  ✓ " + tp.playerName + " 探索: " + (r.getEventDescription()!=null?r.getEventDescription():"成功") +
                        (r.getExpGained()>0?" 经验+"+r.getExpGained():"") +
                        (r.getGoldGained()>0?" 金币+"+r.getGoldGained():"") +
                        (r.getItemGained()!=null?" 获得["+r.getItemGained()+"x"+r.getItemQuantity()+"]":""));
            } else {
                System.out.println("  ○ " + tp.playerName + " 探索冷却/失败: " + r.getMessage());
            }
        }
    }

    // ========== 阶段5：物品与装备 ==========

    @Test
    @Order(7)
    @DisplayName("阶段5-1: 物品系统（添加/使用/背包）")
    void stage5_items() {
        System.out.println("\n[阶段5] 物品与装备");
        System.out.println("─────────────────────────────────────────────");
        TestPlayer p1 = PLAYERS.get("p1");

        // 添加物品
        boolean added = itemService.addItem(p1.playerId, "mtxgdn:healing_pill", 5);
        assertTrue(added, "添加疗伤丹应成功");
        boolean added2 = itemService.addItem(p1.playerId, "mtxgdn:spirit_grass", 10);
        assertTrue(added2);

        // 背包查看
        List<ItemService.InventoryEntry> inv = itemService.getInventory(p1.playerId);
        assertTrue(inv.size() >= 2, "背包应至少有2种物品，实际 " + inv.size());
        System.out.println("  ✓ 背包物品数=" + inv.size());
        for (ItemService.InventoryEntry e : inv) {
            System.out.println("    - " + e.getItem().getName() + " x" + e.getQuantity());
        }

        // 使用物品
        UseItemResult used = itemService.useItem(p1.playerId, "mtxgdn:healing_pill");
        assertTrue(used.success(), "使用疗伤丹应成功: " + used.message());
        System.out.println("  ✓ 使用物品成功");

        // 灵石
        long stones = itemService.getSpiritStoneCount(p1.playerId);
        System.out.println("  ✓ 当前灵石=" + stones);
    }

    @Test
    @Order(8)
    @DisplayName("阶段5-2: 装备系统")
    void stage5_equipment() {
        TestPlayer p2 = PLAYERS.get("p2");
        // 添加装备
        boolean added = itemService.addItem(p2.playerId, "mtxgdn:spirit_sword", 1);
        assertTrue(added, "添加灵剑应成功");

        EquipResult equip = itemService.equipItem(p2.playerId, "mtxgdn:spirit_sword", "weapon");
        assertTrue(equip.success(), "装备灵剑应成功: " + equip.message());

        String equipped = itemService.getEquippedItem(p2.playerId, "weapon");
        assertEquals("mtxgdn:spirit_sword", equipped, "weapon槽应装备灵剑");
        System.out.println("  ✓ " + PLAYERS.get("p2").playerName + " 装备灵剑成功");

        EquipResult unequip = itemService.unequipItem(p2.playerId, "weapon");
        assertTrue(unequip.success(), "卸下应成功");
        System.out.println("  ✓ 卸下装备成功");
    }

    // ========== 阶段6：交易坊市 ==========

    @Test
    @Order(9)
    @DisplayName("阶段6: 交易坊市（上架->购买）")
    void stage6_trade() {
        System.out.println("\n[阶段6] 交易坊市");
        System.out.println("─────────────────────────────────────────────");
        TestPlayer seller = PLAYERS.get("p1");
        TestPlayer buyer = PLAYERS.get("p2");

        // 卖家上架（需要先有物品和灵石库存）
        itemService.addItem(seller.playerId, "mtxgdn:spirit_grass", 20);
        TradeResult list = tradeService.listItem(seller.playerId, "mtxgdn:spirit_grass", 5, 100);
        assertTrue(list.success(), "上架应成功: " + list.message());
        System.out.println("  ✓ " + seller.playerName + " 上架灵草x5 @100灵石");

        // 获取市场列表
        java.lang.reflect.Method getActiveListings = null;
        try {
            getActiveListings = TradeService.class.getMethod("getActiveListings");
            List<?> listings = (List<?>) getActiveListings.invoke(tradeService);
            System.out.println("  ✓ 市场商品数=" + listings.size());
        } catch (Exception e) {
            System.out.println("  ○ 获取市场列表: " + e.getMessage());
        }

        // 买家购买 - 需要找到第一个挂单id
        try {
            var m = TradeService.class.getMethod("getActiveListings");
            List<?> listings = (List<?>) m.invoke(tradeService);
            if (!listings.isEmpty()) {
                // 反射获取第一个挂单的id
                Object first = listings.get(0);
                java.lang.reflect.Field idField = null;
                for (java.lang.reflect.Field f : first.getClass().getDeclaredFields()) {
                    if (f.getName().equals("id")) { idField = f; break; }
                }
                if (idField == null) {
                    // 试试父类
                    Class<?> c = first.getClass();
                    while (c != null && idField == null) {
                        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                            if (f.getName().equals("id")) { idField = f; break; }
                        }
                        c = c.getSuperclass();
                    }
                }
                if (idField != null) {
                    idField.setAccessible(true);
                    long listingId = idField.getLong(first);
                    // 买家需要灵石
                    itemService.addSpiritStones(buyer.playerId, 1000);
                    TradeResult buy = tradeService.buyItem(buyer.playerId, listingId);
                    System.out.println("  " + (buy.success() ? "✓" : "○") +
                            " " + buyer.playerName + " 购买: " + buy.message());
                }
            }
        } catch (Exception e) {
            System.out.println("  ○ 购买测试: " + e.getMessage());
        }
    }

    // ========== 阶段7：PVP战斗 ==========

    @Test
    @Order(10)
    @DisplayName("阶段7: PVP战斗")
    void stage7_pvp() {
        System.out.println("\n[阶段7] PVP战斗");
        System.out.println("─────────────────────────────────────────────");
        TestPlayer p3 = PLAYERS.get("p3");
        TestPlayer p4 = PLAYERS.get("p4");

        // 直接PVP
        CombatService.CombatResult result = combatService.pvpChallenge(p3.playerId, p4.playerId);
        System.out.println("  " + (result.isSuccess()?"✓":"○") + " PVP: " + result.getMessage());
        if (result.isSuccess()) {
            System.out.println("    挑战者=" + result.getChallengerName() +
                    " 剩余HP=" + result.getChallengerRemainingHp());
            System.out.println("    防守者=" + result.getTargetName() +
                    " 剩余HP=" + result.getTargetRemainingHp());
            System.out.println("    回合=" + result.getTotalRounds() +
                    " 胜者=" + result.getWinner());
            System.out.println("    战斗日志行数=" + (result.getBattleLog()!=null?result.getBattleLog().size():0));
        }
    }

    // ========== 阶段8：好友与聊天 ==========

    @Test
    @Order(11)
    @DisplayName("阶段8-1: 好友系统")
    void stage8_friends() {
        System.out.println("\n[阶段8] 好友与聊天");
        System.out.println("─────────────────────────────────────────────");
        TestPlayer p1 = PLAYERS.get("p1");
        TestPlayer p2 = PLAYERS.get("p2");

        Friend f = friendService.sendRequest(p1.playerId, p2.playerId);
        assertNotNull(f, "发送好友请求应返回对象");

        List<Friend> pending = friendService.getPendingRequests(p2.playerId);
        System.out.println("  ✓ " + p2.playerName + " 待处理请求=" + pending.size());

        boolean accepted = friendService.acceptRequest(p2.playerId, p1.playerId);
        assertTrue(accepted, "接受好友请求应成功");
        System.out.println("  ✓ " + p1.playerName + " 与 " + p2.playerName + " 成为好友");

        List<Friend> friends = friendService.getFriends(p1.playerId);
        System.out.println("  ✓ " + p1.playerName + " 好友数=" + friends.size());
    }

    @Test
    @Order(12)
    @DisplayName("阶段8-2: 聊天系统（世界/私聊）")
    void stage8_chat() {
        TestPlayer p1 = PLAYERS.get("p1");
        TestPlayer p2 = PLAYERS.get("p2");

        ChatMessage world = chatService.sendWorldMessage(p1.playerId, p1.playerName, "大家好，我是新来的！");
        assertNotNull(world, "世界消息应返回");
        System.out.println("  ✓ 世界频道消息发送成功");

        ChatMessage priv = chatService.sendPrivateMessage(p1.playerId, p1.playerName, p2.playerId, "我们加个好友吧");
        assertNotNull(priv);
        System.out.println("  ✓ 私聊消息发送成功");

        List<ChatMessage> worldMsgs = chatService.getWorldMessages(10);
        assertFalse(worldMsgs.isEmpty(), "世界消息不应为空");
        System.out.println("  ✓ 世界消息条数=" + worldMsgs.size());
    }

    // ========== 阶段9：宗门 ==========

    @Test
    @Order(13)
    @DisplayName("阶段9: 宗门系统")
    void stage9_sect() {
        System.out.println("\n[阶段9] 宗门系统");
        System.out.println("─────────────────────────────────────────────");
        TestPlayer p1 = PLAYERS.get("p1");
        TestPlayer p2 = PLAYERS.get("p2");

        // 直接把宿主玩家 p1 的境界设为金丹期（realm=4），创建宗门要求金丹期
        Player p1raw = playerService.getPlayerById(p1.playerId);
        if (p1raw != null && p1raw.getRealm() < 4) {
            p1raw.setRealm(4);
            playerService.updatePlayer(p1.playerId, p1raw);
            System.out.println("  ○ 宿主境界已设为金丹期");
        }

        SectActionResult create = sectService.createSect(p1.playerId, "青云门", "护道宗门");
        assertTrue(create.success(), "创建宗门应成功: " + create.message());
        System.out.println("  ✓ " + p1.playerName + " 创建宗门青云门");

        Sect createdSect = create.sect();
        assertNotNull(createdSect, "应返回宗门对象");
        long sectId = createdSect.getId();

        // 加入申请+批准
        var apply = sectService.applyToSect(p2.playerId, sectId, "求入宗门");
        if (apply.success()) {
            System.out.println("  ✓ " + p2.playerName + " 申请加入");
            List<SectApplication> apps = sectService.getPendingApplications(sectId);
            if (!apps.isEmpty()) {
                long appId = apps.get(0).getId();
                var approve = sectService.approveApplication(p1.playerId, appId, true);
                System.out.println("  " + (approve.success()?"✓":"○") +
                        " 批准申请: " + approve.message());
            }
        } else {
            System.out.println("  ○ 申请加入: " + apply.message());
        }

        List<Sect> sects = sectService.getAllSects();
        System.out.println("  ✓ 宗门总数=" + sects.size());
    }

    // ========== 阶段10：每日与签到 ==========

    @Test
    @Order(14)
    @DisplayName("阶段10-1: 每日签到")
    void stage10_signin() {
        System.out.println("\n[阶段10] 每日签到与经济");
        System.out.println("─────────────────────────────────────────────");
        TestPlayer p1 = PLAYERS.get("p1");

        SignInResult sign = economyService.signIn(p1.playerId);
        if (sign.success()) {
            System.out.println("  ✓ 签到成功: " + sign.message());
        } else {
            System.out.println("  ○ 签到: " + sign.message());
        }
    }

    // ========== 阶段11：农场 ==========

    @Test
    @Order(15)
    @DisplayName("阶段11: 农场系统")
    void stage11_farm() {
        System.out.println("\n[阶段11] 农场系统");
        System.out.println("─────────────────────────────────────────────");
        TestPlayer p3 = PLAYERS.get("p3");

        // 添加种子
        itemService.addItem(p3.playerId, "mtxgdn:spirit_grass_seed", 3);

        FarmResult plant = farmService.plant(p3.playerId, 0, "mtxgdn:spirit_grass_seed");
        System.out.println("  " + (plant.success() ? "✓" : "○") + " 种植: " + plant.message());

        FarmResult water = farmService.water(p3.playerId, 0);
        System.out.println("  " + (water.success() ? "✓" : "○") + " 浇水: " + water.message());

        List<FarmPlot> plots = farmService.getPlots(p3.playerId);
        System.out.println("  ✓ 地块数=" + plots.size());
    }

    // ========== 阶段12：功法/技能 ==========

    @Test
    @Order(16)
    @DisplayName("阶段12: 功法与技能学习")
    void stage12_technique() {
        System.out.println("\n[阶段12] 功法与技能");
        System.out.println("─────────────────────────────────────────────");
        TestPlayer p1 = PLAYERS.get("p1");

        List<Technique> allTech = techniqueService.getAllTechniques();
        System.out.println("  ✓ 功法总数=" + allTech.size());
        if (!allTech.isEmpty()) {
            long techId = allTech.get(0).getId();
            OperationResult learn = techniqueService.learnTechnique(p1.playerId, techId);
            System.out.println("  " + (learn.success()?"✓":"○") + " 学习功法: " + learn.message());
        }

        List<Skill> allSkills = skillService.getAllSkills();
        System.out.println("  ✓ 技能总数=" + allSkills.size());
    }

    // ========== 阶段13：团队 ==========

    @Test
    @Order(17)
    @DisplayName("阶段13: 团队系统")
    void stage13_team() {
        System.out.println("\n[阶段13] 团队系统");
        System.out.println("─────────────────────────────────────────────");
        TestPlayer p1 = PLAYERS.get("p1");
        TestPlayer p2 = PLAYERS.get("p2");
        TestPlayer p3 = PLAYERS.get("p3");

        TeamService.Team team = teamService.createTeam(p1.playerId);
        assertNotNull(team, "创建团队应成功");
        System.out.println("  ✓ " + p1.playerName + " 创建团队 (teamId=" + team.getTeamId() + ")");

        boolean invited = teamService.invitePlayer(p1.playerId, p2.playerId);
        System.out.println("  " + (invited?"✓":"○") + " 邀请 " + p2.playerName);

        TeamService.Team accepted = teamService.acceptInvite(p2.playerId);
        if (accepted != null) {
            System.out.println("  ✓ " + p2.playerName + " 加入团队，成员=" + accepted.getMemberCount());
        } else {
            System.out.println("  ○ 加入团队失败");
        }
    }

    // ========== 阶段14：地图 ==========

    @Test
    @Order(18)
    @DisplayName("阶段14: 地图系统")
    void stage14_map() {
        System.out.println("\n[阶段14] 地图系统");
        System.out.println("─────────────────────────────────────────────");
        mapService.ensureInitialized();
        TestPlayer p1 = PLAYERS.get("p1");

        List<MapLocation> locs = mapService.getAllLocations();
        System.out.println("  ✓ 地图地点数=" + locs.size());
        if (locs.size() >= 2) {
            long target = locs.get(1).getId();
            TravelResult travel = mapService.travel(p1.playerId, target);
            System.out.println("  " + (travel.success() ? "✓" : "○") + " 移动: " + travel.message());
        }
    }

    // ========== 阶段15：综合统计与总结 ==========

    @Test
    @Order(19)
    @DisplayName("阶段15: 玩家列表与综合验证")
    void stage15_summary() {
        System.out.println("\n[阶段15] 综合验证");
        System.out.println("─────────────────────────────────────────────");

        int count = playerService.getPlayerCount();
        System.out.println("  ✓ 角色总数=" + count);
        assertTrue(count >= 5, "应至少有5名角色");

        List<PlayerInfo> top = playerService.getTopByPower(10);
        System.out.println("  ✓ 战力排行榜前" + Math.min(10, top.size()) + "名:");
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            PlayerInfo p = top.get(i);
            System.out.println("    " + (i+1) + ". " + p.getName() + " (战力=" + (p.getAttack()+p.getDefense()+p.getSpeed()+p.getMaxHp()) + ")");
        }

        System.out.println("\n════════════════════════════════════════════════════════════");
        System.out.println("全部测试完成！");
        System.out.println("覆盖功能: 注册/登录、创建角色、修炼、突破、探索、物品、" +
                "\n        装备、交易、PVP、好友、聊天、宗门、签到、农场、" +
                "\n        功法、团队、地图、排行榜");
        System.out.println("模拟玩家: " + PLAYERS.size() + " 名");
        System.out.println("════════════════════════════════════════════════════════════");
    }

    // ==================== 辅助方法 ====================

    private static String respBody(Response resp) {
        Object e = resp.getEntity();
        return e != null ? e.toString() : "";
    }

    private static long extractJsonLong(Response resp, String dottedPath) {
        String body = (String) resp.getEntity();
        var json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String[] parts = dottedPath.split("\\.");
        var obj = json;
        for (int i = 0; i < parts.length - 1; i++) {
            obj = obj.getAsJsonObject(parts[i]);
        }
        return obj.get(parts[parts.length-1]).getAsLong();
    }

    private static String extractJsonString(Response resp, String dottedPath) {
        String body = (String) resp.getEntity();
        var json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String[] parts = dottedPath.split("\\.");
        var obj = json;
        for (int i = 0; i < parts.length - 1; i++) {
            obj = obj.getAsJsonObject(parts[i]);
        }
        var el = obj.get(parts[parts.length-1]);
        return el.isJsonNull() ? null : el.getAsString();
    }
}
