package com.mtxgdn.common.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「服务层返回值无类型」这笔债的棘轮。
 *
 * <p><b>缺陷形态</b>：服务层有上百个 public 方法返回 {@code Map<String, Object>}，
 * 里面塞的是 {@code success} / {@code message} / 各种业务字段。调用方只能靠
 * 字符串键取值再强转，键名写错、类型转错都只有运行时才炸。</p>
 *
 * <p><b>为什么不是一次性全改</b>：其中一部分方法需要带回各自不同的数据
 * （战报、仓库列表、结算明细、拍卖出价……）。把它们塞进同一个
 * {@link OperationResult} 会逼着调用方继续 {@code get("battleLog")}，
 * 等于把问题搬了个位置。正确做法是每个方法配一个字段有类型的 record ——
 * 那是一次按服务推进的重构，不是机械替换。</p>
 *
 * <p><b>这道测试的作用</b>：把「还剩哪些」变成一份可执行清单，并且是<b>只许缩小</b>的棘轮。</p>
 * <ul>
 *   <li>新增一个返回 {@code Map<String, Object>} 的 public 方法 → 红。
 *       想加就得先想清楚：这个返回值该不该有类型？</li>
 *   <li>把某个方法改成 record 之后忘了从清单里删 → 也红（清单必须跟着缩小）。</li>
 * </ul>
 *
 * <p>已经改成 {@link OperationResult} 的那批方法（宗门成员域 6 个）不在这份清单里 ——
 * 它们已经不在扫描结果中，正是棘轮转过一格的样子。</p>
 */
class ResultMapDebtTest {

    private static final Path MAIN = Paths.get("src/main/java");

    /** {@code public [static] Map<String, Object> name(} */
    private static final Pattern UNTYPED_RESULT = Pattern.compile(
            "^\\s*public\\s+(?:static\\s+)?Map<String,\\s*Object>\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);

    /**
     * 这些返回 {@code Map<String, Object>} 是<b>对的</b>，不该 DTO 化。
     *
     * <p>共同点：键集合在编译期根本不可知 —— 要么来自 JSON/配置文件，
     * 要么来自任意表的列名，要么是给外部看的通用状态快照。</p>
     */
    private static final Set<String> LEGITIMATELY_DYNAMIC = Set.of(
            // 配置读取：键来自外部 JSON，数量与名字随配置变
            "com/mtxgdn/adapter/AdapterConfig.java#getConfig",
            "com/mtxgdn/plugin/gui/PluginConfig.java#toMap",
            "com/mtxgdn/util/AppConfig.java#loadClasspathOrDefault",
            "com/mtxgdn/util/AppConfig.java#loadExternal",
            "com/mtxgdn/util/AppConfig.java#deepMerge",
            "com/mtxgdn/util/AppConfig.java#flatten",
            // JSON 解析：本来就是在把任意 JSON 变成 Map
            "com/mtxgdn/plugin/gui/MiniJson.java#parseObject",
            "com/mtxgdn/plugin/event/PluginEvent.java#getData",
            // 通用行映射：列名由调用方给的表名决定
            "com/mtxgdn/db/DatabaseManager.java#getRowById",
            "com/mtxgdn/db/EconomyDAO.java#findBankDepositRow",
            "com/mtxgdn/db/EconomyDAO.java#rowToMap",
            // 运行时状态快照：给运维看的，字段随实现变
            "com/mtxgdn/i18n/I18nManager.java#status",
            // OAuth 协议响应：字段由 RFC 规定，且要能带任意扩展字段
            "com/mtxgdn/service/OAuthService.java#getConfig",
            "com/mtxgdn/service/OAuthService.java#introspect",
            "com/mtxgdn/service/OAuthService.java#buildTokenResponse");

    /**
     * 待改的清单：游戏服务层的操作结果。
     *
     * <p>每一条都应该在未来的某次按服务重构里变成字段有类型的 record。
     * 改完一个就从这里删一行 —— 删不掉说明清单和代码不一致，测试会红。</p>
     */
    private static final List<String> PENDING_DTO = List.of(
            // ---- 洞府 ----
            "com/mtxgdn/game/service/CaveService.java#createCave",
            "com/mtxgdn/game/service/CaveService.java#collectSpiritEnergy",
            "com/mtxgdn/game/service/CaveService.java#meditate",
            "com/mtxgdn/game/service/CaveService.java#levelUp",
            // ---- 每日 ----
            "com/mtxgdn/game/service/DailyService.java#doMorningCultivation",
            "com/mtxgdn/game/service/DailyService.java#getDailyInfo",
            // ---- 经济 ----
            "com/mtxgdn/game/service/EconomyService.java#signIn",
            "com/mtxgdn/game/service/EconomyService.java#recycleItem",
            "com/mtxgdn/game/service/EconomyService.java#buyFromShop",
            "com/mtxgdn/game/service/EconomyService.java#boostCultivation",
            "com/mtxgdn/game/service/EconomyService.java#exchangeGoldToStones",
            "com/mtxgdn/game/service/EconomyService.java#exchangeStonesToGold",
            "com/mtxgdn/game/service/EconomyService.java#bankDeposit",
            "com/mtxgdn/game/service/EconomyService.java#bankWithdraw",
            "com/mtxgdn/game/service/EconomyService.java#getBankInfo",
            "com/mtxgdn/game/service/EconomyService.java#getEconomyStats",
            "com/mtxgdn/game/service/EconomyService.java#createAuction",
            "com/mtxgdn/game/service/EconomyService.java#placeBid",
            "com/mtxgdn/game/service/EconomyService.java#purifySpiritStone",
            // ---- 农田 ----
            "com/mtxgdn/game/service/FarmService.java#plant",
            "com/mtxgdn/game/service/FarmService.java#water",
            "com/mtxgdn/game/service/FarmService.java#fertilize",
            "com/mtxgdn/game/service/FarmService.java#harvest",
            "com/mtxgdn/game/service/FarmService.java#clearPlot",
            "com/mtxgdn/game/service/FarmService.java#usePesticide",
            "com/mtxgdn/game/service/FarmService.java#expandPlot",
            "com/mtxgdn/game/service/FarmService.java#waterAll",
            "com/mtxgdn/game/service/FarmService.java#fertilizeAll",
            "com/mtxgdn/game/service/FarmService.java#harvestAll",
            // ---- 阵法 ----
            "com/mtxgdn/game/service/FormationService.java#placeFormationFromTreasure",
            // ---- 物品 ----
            "com/mtxgdn/game/service/ItemService.java#exchangeSpiritStones",
            "com/mtxgdn/game/service/ItemService.java#equipItem",
            "com/mtxgdn/game/service/ItemService.java#unequipItem",
            "com/mtxgdn/game/service/ItemService.java#useItem",
            // ---- 地图 ----
            "com/mtxgdn/game/service/MapService.java#travel",
            "com/mtxgdn/game/service/MapService.java#teleportToLocation",
            "com/mtxgdn/game/service/MapService.java#teleportByUnlock",
            "com/mtxgdn/game/service/MapService.java#getPlayerSurroundings",
            "com/mtxgdn/game/service/MapService.java#getWildernessStats",
            "com/mtxgdn/game/service/MapService.java#gather",
            // ---- 玩家 ----
            // ---- 宗门（带额外数据的那些，成员域 6 个已改） ----
            "com/mtxgdn/game/service/SectService.java#createSect",
            "com/mtxgdn/game/service/SectService.java#levelUp",
            "com/mtxgdn/game/service/SectService.java#transferLeader",
            "com/mtxgdn/game/service/SectService.java#disbandSect",
            "com/mtxgdn/game/service/SectService.java#donateToWarehouse",
            "com/mtxgdn/game/service/SectService.java#withdrawFromWarehouse",
            "com/mtxgdn/game/service/SectService.java#declareWar",
            "com/mtxgdn/game/service/SectService.java#stepDown",
            "com/mtxgdn/game/service/SectService.java#challengeLeader",
            "com/mtxgdn/game/service/SectService.java#donateStonesToTreasury",
            "com/mtxgdn/game/service/SectService.java#getTreasuryInfo",
            "com/mtxgdn/game/service/SectService.java#setSalary",
            "com/mtxgdn/game/service/SectService.java#paySalariesNow",
            // ---- 坊市 ----
            "com/mtxgdn/game/service/TradeService.java#listItem",
            "com/mtxgdn/game/service/TradeService.java#buyItem",
            "com/mtxgdn/game/service/TradeService.java#cancelListing",
            "com/mtxgdn/game/service/TradeService.java#getPriceHistory",
            // ---- 存档 ----
            "com/mtxgdn/playerdata/PlayerDataService.java#exportPlayer",
            "com/mtxgdn/playerdata/PlayerDataService.java#exportAllPlayers",
            "com/mtxgdn/playerdata/PlayerDataService.java#getStats",
            "com/mtxgdn/playerdata/PlayerDataService.java#restorePlayer",
            "com/mtxgdn/playerdata/PlayerDataService.java#restoreFromFile",
            "com/mtxgdn/playerdata/PlayerDataService.java#verifyFile");

    @Test
    @DisplayName("棘轮: 返回无类型 Map 的 public 方法只能变少，不能变多")
    void untypedResultInventoryOnlyShrinks() throws IOException {
        Set<String> actual = scanUntypedResults();

        List<String> unexpected = new ArrayList<>();
        for (String key : actual) {
            if (!LEGITIMATELY_DYNAMIC.contains(key) && !PENDING_DTO.contains(key)) {
                unexpected.add(key);
            }
        }
        assertTrue(unexpected.isEmpty(),
                "这些新方法返回了 Map<String, Object>。请改用字段有类型的 record"
                        + "（只有键恰好是 success + message 的才用 OperationResult；"
                        + "要带数据的请各自定义 record）：\n" + String.join("\n", unexpected));
    }

    @Test
    @DisplayName("棘轮: 清单里不能有已改完却忘删的条目")
    void pendingListHasNoStaleEntries() throws IOException {
        Set<String> actual = scanUntypedResults();

        List<String> stale = new ArrayList<>();
        for (String key : PENDING_DTO) {
            if (!actual.contains(key)) {
                stale.add(key);
            }
        }
        assertFalse(!stale.isEmpty(),
                "这些条目已经不在代码里了（可能已改成 record）。请把它们从 PENDING_DTO 删掉 ——"
                        + "这份清单必须跟着缩小，否则它就成了一份假的进度表：\n"
                        + String.join("\n", stale));
    }

    @Test
    @DisplayName("分类: 同一方法不能既算「动态 Map」又算「待改」")
    void categoriesAreDisjoint() {
        for (String key : LEGITIMATELY_DYNAMIC) {
            assertFalse(PENDING_DTO.contains(key),
                    key + " 同时出现在两个清单里，分类自相矛盾");
        }
    }

    @Test
    @DisplayName("DTO: OperationResult 的 JSON 字段名必须仍是 success / message")
    void operationResultKeepsWireNames() {
        List<String> components = java.util.Arrays.stream(OperationResult.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertEquals(List.of("success", "message"), components,
                "record 组件名决定 Gson 输出的 JSON 键，改了前端就拿不到字段了");
        assertTrue(OperationResult.ok("ok").success());
        assertFalse(OperationResult.fail("no").success());
        assertEquals("no", OperationResult.fail("no").message());
    }

    // ==================== 夹具 ====================

    /** 扫描 {@code src/main/java} 下所有返回 {@code Map<String, Object>} 的 public 方法。 */
    private static Set<String> scanUntypedResults() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = UNTYPED_RESULT.matcher(code);
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                Set<String> methods = new LinkedHashSet<>();
                while (m.find()) {
                    methods.add(m.group(1));
                }
                for (String method : methods) {
                    found.add(relative + "#" + method);
                }
            }
        }
        return found;
    }

    /** 剥掉块注释与行注释：把关键字写在注释里不算数。 */
    private static String stripComments(String code) {
        String noBlock = code.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\\R")) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }
}
