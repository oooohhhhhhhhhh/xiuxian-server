package com.mtxgdn.db;

import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.service.PlayerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护「{@code PlayerService} 里的裸 JDBC 抽到 {@link PlayerDAO}」这次纯结构重构。
 *
 * <p><b>缺陷形态</b>：{@code PlayerService} 一个类里 1450 行、39 处
 * {@code DatabaseManager.getConnection()}，业务计算与 {@code PreparedStatement} /
 * {@code ResultSet} / {@code SQLException} 混在一起。它带来的不是"文件长"，
 * 而是三件具体的事：</p>
 * <ol>
 *   <li>改一列要在服务层里翻半天，而且 {@code mapPlayer} 与 {@code mapPlayerInfo}
 *       是两份各自维护的列清单，漏一列不会编译失败；</li>
 *   <li>"哪条语句在事务里"看不出来 —— 一个方法里既有事务又有随手开的短连接时，
 *       谁在事务里全靠人眼判断；</li>
 *   <li>SQLite 上最致命的一条：事务里若用<b>另一个连接</b>读写同一张表，
 *       会抛 {@code SQLITE_BUSY_SNAPSHOT}（不受 {@code busy_timeout} 约束）
 *       或读到旧快照。所以 DAO 的每个方法都必须有"收调用方连接"的重载。</li>
 * </ol>
 *
 * <p>本测试钉住四类不变量：</p>
 * <ol>
 *   <li><b>公开 API 逐字不变</b>：{@code javap -public} 黄金清单（重构前后导出一致）；</li>
 *   <li><b>服务层不再有 JDBC</b>：{@code PlayerService} 源码里 {@code DatabaseManager} /
 *       {@code PreparedStatement} / {@code ResultSet} 一处不剩；</li>
 *   <li><b>SQL 守恒</b>：原 {@code PlayerService} 里的每一条 SQL 都还在
 *       {@link PlayerDAO} 里（去空白后比对，只允许"搬走"，不允许"改掉/丢掉"）；</li>
 *   <li><b>连接与事务</b>：每个 DAO 方法都有 {@code Connection} 首参重载，
 *       而<b>收连接的方法绝不自己开连接</b>；任何事务 lambda 体内也不许再开连接。
 *       另配真跑 SQLite 的集成测试：写入后读回一致、事务内用 DAO 重载不会
 *       抛 {@code SQLITE_BUSY_SNAPSHOT}。</li>
 * </ol>
 *
 * <p>源码级断言一律先剥掉注释（见 {@link #stripComments}）：把关键字写在注释里
 * 不算数，也不允许注释文本冒充断言锚点。</p>
 */
class PlayerDaoExtractionTest extends TestBase {

    private static final Path PLAYER_SERVICE = Paths.get("src/main/java/com/mtxgdn/game/service/PlayerService.java");
    private static final Path PLAYER_DAO = Paths.get("src/main/java/com/mtxgdn/db/PlayerDAO.java");

    private static final PlayerDAO DAO = new PlayerDAO();

    /** 匹配"方法声明"，形如 {@code public void addHp(Connection conn, ...)}。 */
    private static final java.util.regex.Pattern CONNECTION_METHOD_DECL =
            java.util.regex.Pattern.compile("(?m)^[ \\t]*(?:public|private|protected)[^;{}\\n]*\\(Connection conn");

    private final PlayerService playerService = ServiceRegistry.getPlayerService();

    private static final AtomicInteger SEQ = new AtomicInteger();

    /**
     * {@code javap -public -classpath target/classes com.mtxgdn.game.service.PlayerService}
     * 在<b>重构前</b>导出的公开成员清单（逐字抄录，去掉行尾分号与 {@code throws} 子句，
     * 与 {@link #describePublicApi(Class)} 的输出格式一致）。
     */
    private static final List<String> GOLDEN_SERVICE_API = List.of(
            "public static final int MAX_PLAYER_NAME_LENGTH",
            "public com.mtxgdn.game.service.PlayerService()",
            "public static void shutdown()",
            "public com.mtxgdn.game.entity.PlayerInfo getPlayerByUserId(long)",
            "public com.mtxgdn.entity.Player getPlayerById(long)",
            "public com.mtxgdn.game.entity.PlayerInfo getPlayerInfoById(long)",
            "public boolean setLanguage(long, java.lang.String)",
            "public com.mtxgdn.entity.Player getPlayerByUserIdRaw(long)",
            "public com.mtxgdn.game.entity.PlayerInfo createPlayer(long, java.lang.String)",
            "public void updatePlayer(long, com.mtxgdn.entity.Player)",
            "public boolean updatePlayerName(long, java.lang.String)",
            "public void addExperience(long, long)",
            "public void addExperience(java.sql.Connection, long, long)",
            "public void updateCultivationProgress(long, int)",
            "public void setCultivating(long, boolean)",
            "public int calculateCultivationPerSecond(com.mtxgdn.entity.Player)",
            "public long calculateCultivationExp(com.mtxgdn.entity.Player, long)",
            "public com.mtxgdn.game.cultivation.BeginCultivationResult beginCultivation(long)",
            "public com.mtxgdn.game.cultivation.FinishCultivationResult finishCultivation(long)",
            "public void startCultivation(long, long)",
            "public boolean stopCultivation(long)",
            "public void setTutorialStep(long, int)",
            "public void setTutorialTips(long, int)",
            "public boolean existsByUserId(long)",
            "public void addHp(long, int)",
            "public void damageHp(long, int, int)",
            "public com.mtxgdn.game.cultivation.HealResult healPlayer(long)",
            "public void addMp(long, int)",
            "public void addGold(long, long)",
            "public void addGold(java.sql.Connection, long, long)",
            "public void addAttack(long, int)",
            "public void addAttack(java.sql.Connection, long, int)",
            "public void addDefense(long, int)",
            "public void addDefense(java.sql.Connection, long, int)",
            "public void addSpeed(long, int)",
            "public void addSpeed(java.sql.Connection, long, int)",
            "public void addSpirit(long, int)",
            "public void addSpirit(java.sql.Connection, long, int)",
            "public void updateSpiritualRoot(long, com.mtxgdn.game.entity.SpiritualRoot)",
            "public void updateLastSecretRealmTime(long, long)",
            "public void updateLastExplorationTime(long, long)",
            "public void updateBattleStrategy(long, java.lang.String)",
            "public void updateLastOfflineTime(long, long)",
            "public boolean claimOfflineTime(long, long)",
            "public void incrementSecretRealmCount(long)",
            "public void incrementPvpWins(long)",
            "public void incrementCraftCount(long)",
            "public java.util.List<com.mtxgdn.game.entity.PlayerInfo> getAllPlayers(int, int)",
            "public java.util.List<com.mtxgdn.game.entity.PlayerInfo> searchPlayersByName(java.lang.String, int, int)",
            "public int getPlayerCount()",
            "public int getUserCount()",
            "public java.util.List<com.mtxgdn.game.entity.PlayerInfo> getTopByRealm(int)",
            "public java.util.List<com.mtxgdn.game.entity.PlayerInfo> getTopByPower(int)",
            "public java.util.List<com.mtxgdn.game.entity.PlayerInfo> getTopByWealth(int)",
            "public int getFinalAttack(com.mtxgdn.entity.Player)",
            "public int getFinalDefense(com.mtxgdn.entity.Player)",
            "public int getFinalMaxHp(com.mtxgdn.entity.Player)",
            "public int getFinalMaxMp(com.mtxgdn.entity.Player)",
            "public int getFinalSpeed(com.mtxgdn.entity.Player)",
            "public int getFinalSpirit(com.mtxgdn.entity.Player)",
            "public double getFinalCultivationSpeed(com.mtxgdn.entity.Player)",
            "public double getFinalExpBonus(com.mtxgdn.entity.Player)",
            "public double getFinalDropRateBonus(com.mtxgdn.entity.Player)",
            "public boolean deletePlayer(long)");

    /**
     * 原 {@code PlayerService.java} 里的全部 SQL（逐条抄录，去空白后比对）。
     *
     * <p>这批字符串是"重构前的真身"：只要有一条在 {@link PlayerDAO} 里找不到，
     * 就说明搬运时改了字面量（表名/列名/条件/ORDER BY/LIMIT/参数个数），
     * 而那类改动不会让任何业务测试变红 —— 它只会在生产上悄悄换个结果集。</p>
     */
    private static final List<String> GOLDEN_SQL = List.of(
            // ---- 读取 ----
            "SELECT * FROM players WHERE id = ?",
            "SELECT * FROM players WHERE user_id = ?",
            "SELECT COUNT(*) FROM players WHERE user_id = ?",
            "SELECT COUNT(*) FROM players",
            "SELECT COUNT(*) FROM users",
            // ---- 创角 / 整行保存 / 改名 / 语言 ----
            """
            INSERT INTO players (user_id, name, spiritual_root, level, experience, realm, sub_realm,
                hp, max_hp, mp, max_mp, attack, defense, speed, spirit,
                gold, cultivation_progress, is_cultivating, cultivation_start_time, last_secret_realm_time, last_exploration_time, tutorial_step, tutorial_tips, current_location_id)
            VALUES (?, ?, ?, 1, 0, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 1, 0, 1)
            """,
            """
            UPDATE players SET level = ?, experience = ?, realm = ?, sub_realm = ?,
                hp = ?, max_hp = ?, mp = ?, max_mp = ?, attack = ?, defense = ?,
                speed = ?, spirit = ?, gold = ?,
                cultivation_progress = ?, is_cultivating = ?, cultivation_start_time = ?,
                last_secret_realm_time = ?, last_exploration_time = ?, last_offline_time = ?,
                last_breakthrough_time = ?, last_active_time = ?,
                breakthrough_pity = ?, consolidation = ?
            WHERE id = ?
            """,
            "UPDATE players SET name = ? WHERE id = ?",
            "UPDATE players SET lang = ? WHERE id = ?",
            // ---- 增量修改 ----
            "UPDATE players SET experience = experience + ? WHERE id = ?",
            "UPDATE players SET gold = gold + ? WHERE id = ?",
            "UPDATE players SET hp = MIN(hp + ?, max_hp) WHERE id = ?",
            "UPDATE players SET hp = LEAST(hp + ?, max_hp) WHERE id = ?",
            "UPDATE players SET hp = MAX(?, hp - ?) WHERE id = ?",
            "UPDATE players SET hp = GREATEST(?, hp - ?) WHERE id = ?",
            "UPDATE players SET mp = MIN(mp + ?, max_mp) WHERE id = ?",
            "UPDATE players SET mp = LEAST(mp + ?, max_mp) WHERE id = ?",
            "UPDATE players SET hp = max_hp, mp = max_mp WHERE id = ?",
            "UPDATE players SET spiritual_root = ?, max_hp = ?, hp = MIN(hp, ?), max_mp = ?, mp = MIN(mp, ?), attack = ?, defense = ?, speed = ?, spirit = ? WHERE id = ?",
            "UPDATE players SET spiritual_root = ?, max_hp = ?, hp = LEAST(hp, ?), max_mp = ?, mp = LEAST(mp, ?), attack = ?, defense = ?, speed = ?, spirit = ? WHERE id = ?",
            // ---- 状态位 ----
            "UPDATE players SET cultivation_progress = ? WHERE id = ?",
            "UPDATE players SET is_cultivating = ?, cultivation_start_time = ?, last_offline_time = 0 WHERE id = ?",
            "UPDATE players SET is_cultivating = ?, cultivation_start_time = 0 WHERE id = ? AND is_cultivating = 1",
            "UPDATE players SET tutorial_step = ? WHERE id = ?",
            "UPDATE players SET tutorial_tips = ? WHERE id = ?",
            "UPDATE players SET last_secret_realm_time = ? WHERE id = ?",
            "UPDATE players SET last_exploration_time = ? WHERE id = ?",
            "UPDATE players SET battle_strategy = ? WHERE id = ?",
            "UPDATE players SET last_offline_time = ? WHERE id = ?",
            "UPDATE players SET last_offline_time = 0 WHERE id = ? AND last_offline_time = ?",
            "UPDATE players SET last_active_time = ? WHERE id = ?",
            // ---- 列表 / 榜单 ----
            "SELECT * FROM players ORDER BY level DESC, experience DESC LIMIT ? OFFSET ?",
            "SELECT * FROM players WHERE name LIKE ? ORDER BY level DESC, experience DESC LIMIT ? OFFSET ?",
            "SELECT * FROM players ORDER BY realm DESC, experience DESC LIMIT ?",
            "SELECT * FROM players ORDER BY (attack + defense + speed + max_hp) DESC LIMIT ?",
            """
            SELECT p.*, COALESCE(SUM(CASE
                WHEN pi.item_key = 'mtxgdn:spirit_stone_low' THEN pi.quantity
                WHEN pi.item_key = 'mtxgdn:spirit_stone_mid' THEN pi.quantity * 1000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_high' THEN pi.quantity * 1000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_supreme' THEN pi.quantity * 1000000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone' THEN pi.quantity
                ELSE 0 END), 0) AS spirit_stones
            FROM players p
            LEFT JOIN players_items pi ON p.id = pi.player_id
            GROUP BY p.id
            ORDER BY (p.gold + COALESCE(SUM(CASE
                WHEN pi.item_key = 'mtxgdn:spirit_stone_low' THEN pi.quantity
                WHEN pi.item_key = 'mtxgdn:spirit_stone_mid' THEN pi.quantity * 1000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_high' THEN pi.quantity * 1000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_supreme' THEN pi.quantity * 1000000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone' THEN pi.quantity
                ELSE 0 END), 0)) DESC
            LIMIT ?
            """,
            // ---- 关联查询 ----
            "SELECT title_key FROM player_titles WHERE player_id = ? AND is_equipped = ?",
            "SELECT e.enhance_level FROM players_equipment e LEFT JOIN player_item_durability d ON d.player_id = e.player_id AND d.item_key = e.item_key WHERE e.player_id = ? AND e.enhance_level > 0 AND (d.durability IS NULL OR d.durability > 0)",
            // ---- 级联删除 ----
            "DELETE FROM players WHERE id = ?",
            "DELETE FROM friends WHERE player_id = ? OR friend_player_id = ?",
            "SELECT sect_id, role FROM sect_members WHERE player_id = ?",
            "SELECT COUNT(*) FROM sect_members WHERE sect_id = ? AND player_id != ? AND role IN ('LEADER', 'VICE_LEADER', 'ELDER')",
            "DELETE FROM sect_wars WHERE attacker_sect_id = ? OR defender_sect_id = ?",
            "DELETE FROM sects WHERE id = ?",
            // ---- 后台自动回血 ----
            "UPDATE players SET hp = MIN(hp + CAST(CEILING(max_hp * 0.01) AS INTEGER), max_hp) WHERE hp < max_hp",
            "UPDATE players SET hp = LEAST(hp + CEIL(max_hp * 0.01), max_hp) WHERE hp < max_hp"
    );

    /**
     * 拼进 SQL 的标识符（列名/表名无法参数化）—— 这些语句只能按"源码表达式"守恒：
     * 搬运时既不许换列名，也不许改成"每次拼一句完整 SQL"。
     *
     * <p>与 {@link #GOLDEN_SQL} 一样是逐字抄录的原始 Java 表达式，去空白后比对。</p>
     */
    private static final List<String> GOLDEN_DYNAMIC_SQL = List.of(
            "\"UPDATE players SET \" + column + \" = \" + column + \" + ? WHERE id = ?\"",
            "\"UPDATE players SET \" + column + \" = \" + column + \" + 1 WHERE id = ?\"",
            "\"DELETE FROM \" + table + \" WHERE player_id = ?\"",
            "\"DELETE FROM \" + table + \" WHERE sect_id = ?\""
    );

    // ==================== 1. 公开 API 逐字不变 ====================

    @Test
    @DisplayName("API: PlayerService 的公开成员与重构前逐字一致（javap 黄金清单）")
    void playerServiceKeepsGoldenPublicApi() {
        List<String> actual = describePublicApi(PlayerService.class);
        assertEquals(GOLDEN_SERVICE_API.stream().sorted().toList(), actual,
                "PlayerService 的公开 API 必须与重构前完全一致：多一个、少一个、"
                        + "改一个参数类型都会让 REST / WebSocket / 指令 / 插件 / 其它服务的调用方编译不过");
    }

    // ==================== 2. 服务层不再有 JDBC ====================

    @Test
    @DisplayName("服务层: PlayerService 里 DatabaseManager / PreparedStatement / ResultSet 一处不剩")
    void playerServiceContainsNoJdbc() throws IOException {
        String source = stripComments(read(PLAYER_SERVICE));

        // 白名单为空：本次重构的目标就是让服务层一行 JDBC 都不剩。
        // 如果将来确实需要例外，请在这里加"文件内唯一的一处 + 理由"，并让它尽可能小。
        //
        // 唯一的例外是 DatabaseManager.isReady()：定时任务在每次 tick 开头要问一句
        // "池子还在吗"，这不是 JDBC，是生命周期判断。把这一句剔除之后再断言，
        // 其余任何 DatabaseManager 用法（连接、方言、事务）仍然一律不许出现。
        String withoutReadinessCheck = source.replace("DatabaseManager.isReady()", "");
        assertFalse(withoutReadinessCheck.contains("DatabaseManager"),
                "PlayerService 不该再碰 DatabaseManager：连接、方言差异、事务都应该在 PlayerDAO 里"
                        + "（DatabaseManager.isReady() 是唯一的例外，它只是生命周期判断）");
        assertFalse(source.contains("getConnection("),
                "PlayerService 不该再自己开连接");
        assertFalse(source.contains("PreparedStatement"),
                "PlayerService 不该再出现 PreparedStatement");
        assertFalse(source.contains("ResultSet"),
                "PlayerService 不该再出现 ResultSet");
        assertFalse(source.contains("prepareStatement("),
                "PlayerService 不该再自己拼语句");
        assertFalse(source.contains("executeQuery("),
                "PlayerService 不该再自己执行查询");
        assertFalse(source.contains("executeUpdate("),
                "PlayerService 不该再自己执行更新");

        // 注意：java.sql.Connection / SQLException 仍然出现在公开 API 的形参与 throws 里
        // （addGold(Connection, ...) 等重载被 RealmService / BuffService / TradeService 在事务内调用），
        // 因此这里刻意<b>不</b>断言 "java.sql" 完全消失 —— 那会破坏 API 零改动这条硬约束。
        assertTrue(source.contains("throws SQLException"),
                "收 Connection 的公开重载必须保留 throws SQLException（API 零改动）");
    }

    @Test
    @DisplayName("服务层: 业务逻辑通过 PlayerDAO 访问 players 表")
    void playerServiceDelegatesToPlayerDao() throws Exception {
        Field field = PlayerService.class.getDeclaredField("PLAYER_DAO");
        assertEquals(PlayerDAO.class, field.getType(),
                "PlayerService 必须持有 PlayerDAO 入口，而不是把 SQL 搬回来");

        String source = stripComments(read(PLAYER_SERVICE));
        assertTrue(source.contains("PLAYER_DAO."),
                "PlayerService 必须真的转调 PlayerDAO（否则就是把 SQL 藏到了别处）");
        assertEquals("com.mtxgdn.db", PlayerDAO.class.getPackageName(),
                "PlayerDAO 必须与现有两个 DAO 同包（com.mtxgdn.db）");
    }

    // ==================== 3. SQL 守恒 ====================

    @Test
    @DisplayName("SQL: 原 PlayerService 的每一条 SQL 都逐字出现在 PlayerDAO 里（旧有新无为空）")
    void sqlIsConserved() throws IOException {
        String daoSource = stripComments(read(PLAYER_DAO));
        String fingerprint = squash(String.join("", stringLiterals(daoSource)));

        List<String> missing = new ArrayList<>();
        for (String sql : GOLDEN_SQL) {
            if (!fingerprint.contains(squash(sql))) {
                missing.add(sql.strip().replaceAll("\\R", " "));
            }
        }
        assertTrue(missing.isEmpty(),
                "下面这些 SQL 在 PlayerDAO 里找不到（搬丢了、或被\"顺手优化\"了）。"
                        + "本次重构要求表名/列名/条件/ORDER BY/LIMIT/参数顺序逐字不变：\n"
                        + String.join("\n---\n", missing));
    }

    @Test
    @DisplayName("SQL: 拼列名的动态语句表达式不变（列名仍是编译期常量，不是整句重拼）")
    void dynamicSqlKeepsItsShape() throws IOException {
        String flat = squash(stripComments(read(PLAYER_DAO)));
        List<String> missing = new ArrayList<>();
        for (String expression : GOLDEN_DYNAMIC_SQL) {
            if (!flat.contains(squash(expression))) {
                missing.add(expression);
            }
        }
        assertTrue(missing.isEmpty(),
                "动态 SQL 的拼法变了（列名/表名的拼接方式被改写）：\n" + String.join("\n", missing));
    }

    // ==================== 4. 连接与事务边界 ====================

    @Test
    @DisplayName("DAO: 每个方法都有 Connection 首参重载（事务内可复用调用方的连接）")
    void everyDaoMethodHasConnectionFirstOverload() {
        Set<String> withConnection = new HashSet<>();
        List<String> withoutConnection = new ArrayList<>();
        for (Method m : PlayerDAO.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            boolean connFirst = m.getParameterCount() > 0 && m.getParameterTypes()[0] == Connection.class;
            if (connFirst) {
                withConnection.add(m.getName());
            } else {
                withoutConnection.add(m.getName() + "(" + parameterList(m) + ")");
            }
        }

        // deletePlayerCascade 是唯一豁免：它自己就是"开事务的那一层"，
        // 收外部连接会让"提交/回滚由谁负责"变得含糊（见下一个测试的断言）。
        List<String> offenders = new ArrayList<>();
        for (Method m : PlayerDAO.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() > 0 && m.getParameterTypes()[0] == Connection.class) continue;
            if (m.getName().equals("deletePlayerCascade")) continue;
            if (!withConnection.contains(m.getName())) {
                offenders.add(m.getName() + "(" + parameterList(m) + ")");
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些 DAO 方法没有 Connection 首参重载，事务内调用时只能自己开连接 —— "
                        + "SQLite 下会抛 SQLITE_BUSY_SNAPSHOT 或读到旧快照：\n" + String.join("\n", offenders));

        // 逐个点名关键重载，防止有人把它们改名后"规则依然成立"
        for (String name : List.of("findById", "findByUserId", "findInfoById", "findInfoByUserId",
                "existsByUserId", "insertPlayer", "savePlayer", "addExperience", "addGold",
                "addStat", "updateSpiritualRoot", "stopCultivation", "claimOfflineTime",
                "incrementCounter", "findEquippedTitleKey", "findActiveEnhanceLevels",
                "findTopByRealm", "findTopByPower", "findTopByWealth")) {
            assertTrue(withConnection.contains(name), name + " 缺少 Connection 首参重载");
        }

        // deletePlayerCascade 反过来：它拥有事务，不该收外部连接
        Method cascade = Arrays.stream(PlayerDAO.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("deletePlayerCascade"))
                .findFirst().orElseThrow();
        assertEquals(List.of(long.class), Arrays.asList(cascade.getParameterTypes()),
                "deletePlayerCascade 自己管提交/回滚，只该收 playerId");
        assertTrue(withoutConnection.contains("deletePlayerCascade(long)"));
    }

    @Test
    @DisplayName("DAO: 收 Connection 的方法绝不自己开连接、也不自己开事务")
    void daoConnectionOverloadsNeverOpenTheirOwnConnection() throws IOException {
        String source = stripComments(read(PLAYER_DAO));
        List<String> bodies = methodBodiesTakingConnection(source);
        assertTrue(bodies.size() >= 20,
                "扫描到的 Connection 重载太少（" + bodies.size() + " 个），说明源码解析失效了");

        List<String> offenders = new ArrayList<>();
        for (String body : bodies) {
            if (body.contains("getConnection(")) {
                offenders.add("事务重载内部又开了连接：" + firstLine(body));
            }
            if (body.contains("runTransaction(")) {
                offenders.add("事务重载内部又开了一个事务：" + firstLine(body));
            }
            if (body.contains("setAutoCommit(")) {
                offenders.add("事务重载内部改写了 autocommit：" + firstLine(body));
            }
        }
        assertTrue(offenders.isEmpty(),
                "收 Connection 的重载只能用调用方的连接 —— 自己再开一条会在 SQLite 下死锁/读旧快照：\n"
                        + String.join("\n", offenders));
    }

    @Test
    @DisplayName("事务: 任何 runTransaction lambda 体内都不出现 DatabaseManager.getConnection()")
    void transactionLambdasNeverOpenTheirOwnConnections() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = stripComments(read(file));
                for (String body : transactionLambdaBodies(source)) {
                    if (body.contains("DatabaseManager.getConnection()")) {
                        offenders.add(file.getFileName() + " -> 事务块内调用了 DatabaseManager.getConnection()");
                    }
                    if (body.contains("runTransaction(")) {
                        offenders.add(file.getFileName() + " -> 事务块内又开了一个事务");
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "事务块内再开连接/事务在 SQLite 下会死锁或读到旧快照。"
                        + "需要复用事务时请把 Connection 作为参数传下去：\n" + String.join("\n", offenders));
    }

    // ==================== 5. 真跑 SQLite 的行为验证 ====================

    @Test
    @DisplayName("行为: 写入后读回一致（savePlayer / findById / findInfoById / 增量接口）")
    void writeThenReadBackIsConsistent() throws SQLException {
        long playerId = newPlayer("回读一致性");
        Player loaded = DAO.findById(playerId);
        assertNotNull(loaded, "刚插入的角色必须能按 id 读回");
        assertEquals(playerId, loaded.getId());

        loaded.setLevel(37);
        loaded.setExperience(12345L);
        loaded.setRealm(4);
        loaded.setSubRealm(2);
        loaded.setHp(7);
        loaded.setMaxHp(999);
        loaded.setMp(3);
        loaded.setMaxMp(888);
        loaded.setAttack(11);
        loaded.setDefense(12);
        loaded.setSpeed(13);
        loaded.setSpirit(14);
        loaded.setGold(555L);
        loaded.setCultivationProgress(42);
        loaded.setBreakthroughPity(3);
        loaded.setConsolidation(2);
        DAO.savePlayer(playerId, loaded);

        Player reread = DAO.findById(playerId);
        assertNotNull(reread);
        assertEquals(37, reread.getLevel(), "level 应落库");
        assertEquals(12345L, reread.getExperience(), "experience 应落库");
        assertEquals(4, reread.getRealm(), "realm 应落库");
        assertEquals(2, reread.getSubRealm(), "sub_realm 应落库");
        assertEquals(7, reread.getHp(), "hp 应落库");
        assertEquals(999, reread.getMaxHp(), "max_hp 应落库");
        assertEquals(3, reread.getMp(), "mp 应落库");
        assertEquals(888, reread.getMaxMp(), "max_mp 应落库");
        assertEquals(11, reread.getAttack(), "attack 应落库");
        assertEquals(12, reread.getDefense(), "defense 应落库");
        assertEquals(13, reread.getSpeed(), "speed 应落库");
        assertEquals(14, reread.getSpirit(), "spirit 应落库");
        assertEquals(555L, reread.getGold(), "gold 应落库");
        assertEquals(42, reread.getCultivationProgress(), "cultivation_progress 应落库");
        assertEquals(3, reread.getBreakthroughPity(), "breakthrough_pity 应落库");
        assertEquals(2, reread.getConsolidation(), "consolidation 应落库");

        // 增量接口（在 SQL 里做加减，不能写绝对值）
        DAO.addGold(playerId, 45L);
        assertEquals(600L, DAO.findById(playerId).getGold(), "addGold 必须在 SQL 里累加");
        DAO.addExperience(playerId, 5L);
        assertEquals(12350L, DAO.findById(playerId).getExperience(), "addExperience 必须在 SQL 里累加");
        DAO.addStat(playerId, "attack", 4, "增加攻击力");
        assertEquals(15, DAO.findById(playerId).getAttack(), "addStat 必须在 SQL 里累加");

        // PlayerInfo 是另一套映射，同样要读得回来
        PlayerInfo info = DAO.findInfoById(playerId);
        assertNotNull(info, "PlayerInfo 映射应能按 id 读回");
        assertEquals(37, info.getLevel());
        assertEquals(600L, info.getGold());
        assertEquals(playerId, info.getId());
    }

    @Test
    @DisplayName("行为: 服务层经 DAO 保存/读取仍然正确（updatePlayer → getPlayerById）")
    void serviceLayerSavesAndLoadsThroughTheDao() {
        long playerId = newPlayer("服务层链路");
        Player player = playerService.getPlayerById(playerId);
        assertNotNull(player, "服务层必须能按角色 id 读到玩家");

        player.setLevel(42);
        player.setExperience(4242L);
        player.setGold(777L);
        player.setHp(33);
        player.setMaxHp(333);
        playerService.updatePlayer(playerId, player);

        Player reread = playerService.getPlayerById(playerId);
        assertNotNull(reread);
        assertEquals(42, reread.getLevel());
        assertEquals(4242L, reread.getExperience());
        assertEquals(777L, reread.getGold());
        assertEquals(33, reread.getHp());
        assertEquals(333, reread.getMaxHp());

        playerService.addGold(playerId, 23L);
        assertEquals(800L, playerService.getPlayerById(playerId).getGold(),
                "addGold 走服务层 → DAO，必须落在同一行上");
        assertEquals(42, playerService.getPlayerInfoById(playerId).getLevel(),
                "getPlayerInfoById 是另一套映射，也必须读到同一行");
    }

    @Test
    @DisplayName("行为: 回血/回蓝夹到上限、扣血保留下限（方言分支在 SQLite 上真的生效）")
    void statIncrementsClampOnSqlite() {
        long playerId = newPlayer("夹取测试");
        Player player = playerService.getPlayerById(playerId);
        player.setMaxHp(200);
        player.setHp(50);
        player.setMaxMp(100);
        player.setMp(20);
        playerService.updatePlayer(playerId, player);

        playerService.addHp(playerId, 1000);
        assertEquals(200, playerService.getPlayerById(playerId).getHp(),
                "回血必须夹到 max_hp（MIN/LEAST 分支）");

        playerService.addMp(playerId, 1000);
        assertEquals(100, playerService.getPlayerById(playerId).getMp(),
                "回蓝必须夹到 max_mp（MIN/LEAST 分支）");

        playerService.damageHp(playerId, 500, 7);
        assertEquals(7, playerService.getPlayerById(playerId).getHp(),
                "扣血必须停在下限（MAX/GREATEST 分支）");

        playerService.damageHp(playerId, 0, 0);
        assertEquals(7, playerService.getPlayerById(playerId).getHp(),
                "amount <= 0 时不该写库");
    }

    @Test
    @DisplayName("行为: 榜单/搜索/计数排序与过滤正确")
    void leaderboardsAndSearchWork() {
        long weak = newPlayer("榜单-弱");
        long strong = newPlayer("榜单-强");
        long rich = newPlayer("榜单-富");

        saveRealmAndPower(weak, 97, 10, 300);
        saveRealmAndPower(strong, 99, 10, 900);
        saveRealmAndPower(rich, 98, 5000, 100);
        DAO.addGold(rich, 900_000_000L);

        List<PlayerInfo> byRealm = DAO.findTopByRealm(3);
        assertEquals(List.of(strong, rich, weak),
                List.of(byRealm.get(0).getId(), byRealm.get(1).getId(), byRealm.get(2).getId()),
                "境界榜必须按 realm DESC, experience DESC 排");

        List<PlayerInfo> byPower = DAO.findTopByPower(3);
        assertEquals(rich, byPower.get(0).getId(),
                "战力榜必须按 (attack + defense + speed + max_hp) DESC 排");

        List<PlayerInfo> byWealth = DAO.findTopByWealth(3);
        assertEquals(rich, byWealth.get(0).getId(), "财富榜第一名应是金币最多的角色");

        List<PlayerInfo> all = DAO.findAll(10, 0);
        assertTrue(all.size() >= 3, "分页查询至少能看到这三个角色");
        assertEquals(strong, all.get(0).getId(), "全服列表按 level DESC, experience DESC 排");

        List<PlayerInfo> searched = DAO.searchByName("榜单-富", 10, 0);
        assertEquals(1, searched.size(), "名字模糊搜索应命中唯一一个角色");
        assertEquals(rich, searched.get(0).getId());

        assertEquals(1, DAO.searchByName("榜单-强", 10, 0).size());
        assertTrue(DAO.countPlayers() >= 3, "countPlayers 必须能数到刚插入的角色");
        assertTrue(DAO.countUserAccounts() >= 3, "countUserAccounts 必须能数到账号");
        assertTrue(DAO.existsByUserId(DAO.findById(strong).getUserId()), "existsByUserId 应命中已有角色");
    }

    @Test
    @DisplayName("事务: 事务内用 DAO 的 Connection 重载写入 —— 回滚就真的回滚（证明用的是同一条连接）")
    void daoConnectionOverloadsJoinTheCallerTransaction() throws SQLException {
        long playerId = newPlayer("事务回滚");
        long goldBefore = DAO.findById(playerId).getGold();

        RuntimeException error = assertThrows(RuntimeException.class, () ->
                DatabaseManager.runTransaction(conn -> {
                    Player inTx = DAO.findById(conn, playerId);
                    assertNotNull(inTx, "事务内必须能读到角色");
                    DAO.addGold(conn, playerId, 999L);
                    assertEquals(goldBefore + 999L, DAO.findById(conn, playerId).getGold(),
                            "同一事务内必须立刻读到自己刚写的值（说明用的是同一条连接）");
                    throw new IllegalStateException("故意回滚");
                }));
        assertTrue(String.valueOf(error.getMessage()).contains("故意回滚"),
                "事务里的异常必须原样冒出来，实际: " + error.getMessage());

        assertEquals(goldBefore, DAO.findById(playerId).getGold(),
                "事务已回滚，这 999 金币不该留在库里 —— 若留下了，说明 DAO 在事务里另开了一条"
                        + "（autocommit）连接，SQLite 下正是 SQLITE_BUSY_SNAPSHOT 的来源");
    }

    @Test
    @DisplayName("事务: 有并发写在跑时，事务内调 DAO 重载也不会抛 SQLITE_BUSY_SNAPSHOT")
    void transactionWithConcurrentWriterDoesNotRaiseBusySnapshot() throws Exception {
        long playerId = newPlayer("事务读后写");
        long otherPlayerId = newPlayer("并发写者");

        CountDownLatch txHasRead = new CountDownLatch(1);
        CountDownLatch otherCommitted = new CountDownLatch(1);
        AtomicBoolean interfered = new AtomicBoolean(false);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                txHasRead.await();
                // 另一个连接、另一行、同一张表 —— 它一提交，事务里已有的读快照就过期了
                DAO.addGold(otherPlayerId, 1L);
            } catch (Throwable t) {
                writerFailure.set(t);
            } finally {
                otherCommitted.countDown();
            }
        }, "player-dao-concurrent-writer");
        writer.setDaemon(true);
        writer.start();

        try {
            for (int round = 0; round < 3; round++) {
                int expected = round;
                DatabaseManager.runTransaction(conn -> {
                    Player before = DAO.findById(conn, playerId);
                    assertNotNull(before, "事务内必须读到角色");
                    if (interfered.compareAndSet(false, true)) {
                        // 制造"读后写 + 期间别的连接提交了写"这一形状：
                        // 第一轮的 UPDATE 会撞上 SQLITE_BUSY_SNAPSHOT，重试由 runTransaction 负责，
                        // 它绝不该把这个异常冒给调用方。
                        txHasRead.countDown();
                        assertTrue(otherCommitted.await(10, TimeUnit.SECONDS), "并发写者应在 10 秒内提交");
                    }
                    DAO.addGold(conn, playerId, 1L);
                    assertEquals(before.getGold() + 1L, DAO.findById(conn, playerId).getGold(),
                            "第 " + expected + " 轮：同一事务内必须看到自己的写入");
                    return null;
                });
            }
        } finally {
            writer.join(10_000L);
        }

        assertNull(writerFailure.get(), "并发写线程不该失败: " + writerFailure.get());
        assertEquals(3L, DAO.findById(playerId).getGold(), "3 轮事务的增量必须全部落地");
        assertEquals(1L, DAO.findById(otherPlayerId).getGold(), "并发写者的那笔增量也必须落地");
    }

    @Test
    @DisplayName("事务: 级联删除是 all-or-nothing（失败时整笔回滚，不留半删的角色）")
    void deletePlayerCascadeIsAllOrNothing() throws SQLException {
        long doomed = newPlayer("待删除");
        long survivor = newPlayer("幸免者");
        DAO.addGold(doomed, 12L);
        DAO.addGold(survivor, 34L);

        // 已知缺陷（重构前就存在，本次按硬约束只搬位置、不改任何 SQL 字面量）：
        // 删除清单里的 auction_listings / auction_bids / trade_listings / chat_messages
        // 用的是 player_id 列，但这四张表实际叫 seller_player_id / bidder_player_id /
        // sender_player_id / receiver_player_id，于是 DELETE 到第 12 张表时报
        // "no such column: player_id"，整笔事务回滚。
        // 本断言只锁"事务完整性"这一条不变式：要么角色被完整删掉，要么一行都不动。
        // 缺陷修好之后，这个测试照样通过。
        boolean deleted;
        try {
            deleted = DAO.deletePlayerCascade(doomed);
        } catch (RuntimeException e) {
            assertTrue(String.valueOf(e.getMessage()).contains("删除玩家失败"),
                    "失败文案必须保持原样，实际: " + e.getMessage());
            assertNotNull(DAO.findById(doomed), "删除事务必须整体回滚，不能留下半删的角色");
            assertEquals(12L, DAO.findById(doomed).getGold(), "回滚后数据必须完好无损");
            deleted = false;
        }

        if (deleted) {
            assertNull(DAO.findById(doomed), "删除成功后按 id 应查不到");
        }
        assertNotNull(DAO.findById(survivor), "别的角色不该被删掉");
        assertEquals(34L, DAO.findById(survivor).getGold(), "别的角色的数据不该被动到");
    }

    // ==================== 夹具 ====================

    private static long newPlayer(String name) {
        int n = SEQ.incrementAndGet();
        String username = "playerdao_" + n;
        try {
            long userId = insertUser(username);
            // 不经过 createPlayer：那会带上新手礼包（依赖物品配置），本测试只验证 DAO 自身
            long playerId = DAO.insertPlayer(userId, name + n, SpiritualRoot.LIHUO_FIRE, null);
            assertTrue(playerId > 0, "插入角色应返回自增主键");
            return playerId;
        } catch (SQLException e) {
            throw new RuntimeException("准备角色失败", e);
        }
    }

    private static void saveRealmAndPower(long playerId, int realm, int attack, int maxHp) {
        Player player = DAO.findById(playerId);
        assertNotNull(player);
        player.setRealm(realm);
        player.setLevel(realm);
        player.setExperience(realm * 1000L);
        player.setAttack(attack);
        player.setDefense(1);
        player.setSpeed(1);
        player.setMaxHp(maxHp);
        player.setHp(maxHp);
        DAO.savePlayer(playerId, player);
    }

    private static long insertUser(String username) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (username, password) VALUES (?, ?)")) {
                ps.setString(1, username);
                ps.setString(2, "x");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "用户写入失败: " + username);
                    return rs.getLong(1);
                }
            }
        }
    }

    // ==================== 辅助 ====================

    /** 反射导出与 {@code javap -public} 同格式的公开成员清单（排序后便于逐字比较）。 */
    private static List<String> describePublicApi(Class<?> type) {
        List<String> members = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers())) continue;
            StringBuilder sb = new StringBuilder("public ");
            if (Modifier.isStatic(field.getModifiers())) sb.append("static ");
            if (Modifier.isFinal(field.getModifiers())) sb.append("final ");
            members.add(sb.append(field.getGenericType().getTypeName())
                    .append(' ').append(field.getName()).toString());
        }
        for (var ctor : type.getDeclaredConstructors()) {
            if (!Modifier.isPublic(ctor.getModifiers())) continue;
            StringBuilder sb = new StringBuilder("public ").append(type.getTypeName()).append('(');
            Type[] params = ctor.getGenericParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getTypeName());
            }
            members.add(sb.append(')').toString());
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            StringBuilder sb = new StringBuilder("public ");
            if (Modifier.isStatic(method.getModifiers())) sb.append("static ");
            sb.append(method.getGenericReturnType().getTypeName())
                    .append(' ').append(method.getName()).append('(');
            Type[] params = method.getGenericParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getTypeName());
            }
            members.add(sb.append(')').toString());
        }
        return members.stream().sorted().toList();
    }

    private static String parameterList(Method m) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> p : m.getParameterTypes()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getSimpleName());
        }
        return sb.toString();
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "找不到文件: " + path.toAbsolutePath());
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String read(String relative) throws IOException {
        return read(Paths.get(relative));
    }

    /** 去掉全部空白，用于"SQL 字面量守恒"的比对（换行/缩进不算差异）。 */
    private static String squash(String text) {
        return text.replaceAll("\\s+", "");
    }

    private static String firstLine(String body) {
        String trimmed = body.strip();
        int nl = trimmed.indexOf('\n');
        return nl > 0 ? trimmed.substring(0, nl).strip() : trimmed;
    }

    /**
     * 抽出源码里的全部字符串字面量（含 text block），按出现顺序拼接。
     *
     * <p>拼接后再去空白，是为了让 {@code "A " + "B"} 这种被拆开的 SQL 也能与
     * 完整的 SQL 文本对上 —— 本次重构允许把 SQL 拆成常量，但不允许改内容。</p>
     */
    private static List<String> stringLiterals(String source) {
        List<String> literals = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c != '"') {
                i++;
                continue;
            }
            if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                assertTrue(end > 0, "text block 未闭合：源码解析失败");
                literals.add(source.substring(i + 3, end));
                i = end + 3;
                continue;
            }
            StringBuilder sb = new StringBuilder();
            int j = i + 1;
            while (j < source.length()) {
                char d = source.charAt(j);
                if (d == '\\' && j + 1 < source.length()) {
                    sb.append(source.charAt(j + 1));
                    j += 2;
                    continue;
                }
                if (d == '"') break;
                sb.append(d);
                j++;
            }
            literals.add(sb.toString());
            i = j + 1;
        }
        return literals;
    }

    /**
     * 取出源码里所有"第一个参数是 {@code Connection} 的方法"的声明 + 方法体。
     *
     * <p>只认<b>方法声明</b>（行首访问修饰符 + 名字 + {@code (Connection conn}），
     * 否则 {@code try (Connection conn = DatabaseManager.getConnection())} 这种
     * try-with-resources 也会被误当成方法头，扫描结果就不可信了。</p>
     */
    private static List<String> methodBodiesTakingConnection(String source) {
        List<String> bodies = new ArrayList<>();
        Matcher matcher = CONNECTION_METHOD_DECL.matcher(source);
        while (matcher.find()) {
            int open = source.indexOf('(', matcher.start());
            int close = matchingParen(source, open);
            int brace = source.indexOf('{', close);
            int semi = source.indexOf(';', close);
            // 抽象方法声明（如回调接口里的一行签名）没有方法体，跳过
            if (semi >= 0 && (brace < 0 || semi < brace)) continue;
            int end = matchingBrace(source, brace);
            bodies.add(source.substring(matcher.start(), end + 1));
        }
        return bodies;
    }

    /** 取出每个 {@code DatabaseManager.runTransaction(...)} 调用的实参文本（即 lambda 体）。 */
    private static List<String> transactionLambdaBodies(String source) {
        List<String> bodies = new ArrayList<>();
        String needle = "DatabaseManager.runTransaction(";
        int from = 0;
        int idx;
        while ((idx = source.indexOf(needle, from)) >= 0) {
            int open = idx + needle.length() - 1;
            int close = matchingParen(source, open);
            bodies.add(source.substring(open + 1, close));
            from = close;
        }
        return bodies;
    }

    /** 从 {@code open} 处的 {@code (} 起找配对的 {@code )}，跳过字符串与字符字面量。 */
    private static int matchingParen(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < source.length()) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    j++;
                    if (d == c) break;
                }
                i = j - 1;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        throw new IllegalStateException("括号不匹配：源码扫描失败");
    }

    /** 从 {@code open} 处的 {@code {} 起找配对的 {@code }}，跳过字符串与字符字面量。 */
    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < source.length()) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    j++;
                    if (d == c) break;
                }
                i = j - 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        throw new IllegalStateException("花括号不匹配：源码扫描失败");
    }

    /** 剥掉 {@code //} 与 {@code /* *}{@code /} 注释，保留字符串/字符字面量。 */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                out.append(c);
                int j = i + 1;
                while (j < source.length()) {
                    char d = source.charAt(j);
                    out.append(d);
                    if (d == '\\' && j + 1 < source.length()) {
                        out.append(source.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    j++;
                    if (d == c) break;
                }
                i = j;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
