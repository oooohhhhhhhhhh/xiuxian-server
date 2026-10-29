package com.mtxgdn.game.service;

import com.mtxgdn.game.economy.AuctionResult;
import com.mtxgdn.game.economy.BankDepositResult;
import com.mtxgdn.game.economy.BankInfo;
import com.mtxgdn.game.economy.BankWithdrawResult;
import com.mtxgdn.game.economy.BoostResult;
import com.mtxgdn.game.economy.CurrencyExchangeResult;
import com.mtxgdn.game.economy.DepositView;
import com.mtxgdn.game.economy.EconomyStats;
import com.mtxgdn.game.economy.PurifyResult;
import com.mtxgdn.game.economy.RecycleResult;
import com.mtxgdn.game.economy.ShopPurchaseResult;
import com.mtxgdn.game.economy.SignInResult;
import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.CaveDAO;
import com.mtxgdn.db.EconomyDAO;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.Cave;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;

public class EconomyService {

    private static final GameLogger LOG = GameLogger.getLogger(EconomyService.class);

    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();

    private static final EconomyDAO ECONOMY_DAO = new EconomyDAO();

    /** 提纯要扣洞府灵气：caves 表归 CaveDAO。 */
    private static final CaveDAO CAVE_DAO = new CaveDAO();

    /**
     * 拍卖行到期结算任务。
     *
     * <p>历史上到期结算是纯惰性的：只有在有人打开拍卖列表时才顺手结一次。问题是
     * <b>没人看就永远不结算</b> —— 中标者的物品一直不到账、卖家的灵石一直不入账，
     * 要等下一次有人翻列表才补上。这里补一个定时任务，让结算与"有没有人看"解耦；
     * 惰性结算仍然保留，两者都是幂等的（靠 {@code UPDATE ... WHERE status='active'} 抢占）。</p>
     */
    private static final ScheduledExecutorService AUCTION_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "auction-settler");
                t.setDaemon(true);
                return t;
            });

    /** 结算扫描间隔（秒）。拍卖时长是天级的，60 秒的精度完全够用。 */
    private static final int AUCTION_SWEEP_SECONDS =
            Math.max(10, AppConfig.getInt("market.auction_sweep_seconds", 60));

    static {
        // 首个 tick 延迟 30s：避开启动高峰（与坊市回收任务的 20s 错开）
        AUCTION_SCHEDULER.scheduleWithFixedDelay(() -> {
            try {
                ServiceRegistry.getEconomyService().sweepExpiredAuctions();
            } catch (Throwable t) {
                // 绝不让异常冒泡：一次失败不应终止后续所有 tick
                LOG.warn("拍卖到期结算任务异常: " + t);
            }
        }, 30, AUCTION_SWEEP_SECONDS, TimeUnit.SECONDS);
    }

    /** 停止拍卖结算任务（服务端关闭时调用）。 */
    public static void shutdown() {
        AUCTION_SCHEDULER.shutdown();
    }

    /**
     * 扫一遍到期拍卖并结算。供定时任务与惰性查询共用，幂等。
     */
    public void sweepExpiredAuctions() {
        // 连接池已关闭（停机 / 测试收尾）时直接跳过，否则每 60 秒刷一次无意义的报错
        if (!DatabaseManager.isAvailable()) {
            return;
        }
        settleExpiredAuctions();
    }

    // ==================== 签到系统 ====================

    private static final String[][] SIGN_IN_REWARDS = {
        {"下品灵石 x50", "mtxgdn:spirit_stone_low", "50"},
        {"下品灵石 x80 + 灵草 x2", "mtxgdn:spirit_stone_low", "80", "mtxgdn:spirit_grass", "2"},
        {"下品灵石 x120 + 铁矿石 x3", "mtxgdn:spirit_stone_low", "120", "mtxgdn:iron_ore", "3"},
        {"下品灵石 x160 + 回血丹 x2", "mtxgdn:spirit_stone_low", "160", "mtxgdn:healing_pill", "2"},
        {"下品灵石 x200 + 强化石 x1", "mtxgdn:spirit_stone_low", "200", "mtxgdn:enhance_stone", "1"},
        {"下品灵石 x260 + 回蓝丹 x2", "mtxgdn:spirit_stone_low", "260", "mtxgdn:mana_pill", "2"},
        {"下品灵石 x350 + 修炼丹 x1", "mtxgdn:spirit_stone_low", "350", "mtxgdn:cultivation_elixir", "1"},
    };

    public SignInResult signIn(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Player player = playerService.getPlayerById(playerId);
        if (player == null) return SignInResult.rejected(I18n.t("economy.character_not_found"));

        SignInRecord rec = getSignInRecord(playerId);
        String today = java.time.LocalDate.now().toString();

        if (rec.lastSignIn != null && rec.lastSignIn.equals(today)) {
            return SignInResult.rejected(I18n.t("economy.you_have_already_checked"));
        }

        int newStreak;
        if (rec.lastSignIn != null) {
            java.time.LocalDate last = java.time.LocalDate.parse(rec.lastSignIn);
            if (last.plusDays(1).equals(java.time.LocalDate.now())) {
                newStreak = rec.streak % 7 + 1;
            } else {
                newStreak = 1;
            }
        } else {
            newStreak = 1;
        }

        String[] reward = SIGN_IN_REWARDS[newStreak - 1];

        return DatabaseManager.runTransaction(conn -> {
            try {
                // 事务内行锁复查：防止并发双签（MySQL 行锁/间隙锁，SQLite 单写者串行化）
                String lastSignIn = ECONOMY_DAO.findLastSignInForUpdate(conn, playerId);
                if (today.equals(lastSignIn)) {
                    return SignInResult.rejected(I18n.t("economy.you_have_already_checked"));
                }

                for (int i = 1; i < reward.length; i += 2) {
                    String itemKey = reward[i];
                    int qty = Integer.parseInt(reward[i + 1]);
                    itemService.addItem(conn, playerId, itemKey, qty);
                }

                updateSignIn(conn, playerId, today, newStreak);

                return SignInResult.signed(
                        I18n.t("economy.check_in_successful", newStreak, reward[0]),
                        newStreak, newStreak, reward[0]);
            } catch (SQLException e) {
                return SignInResult.rejected(I18n.t("economy.check_in_failed", e.getMessage()));
            }
        });
    }

    public SignInRecord getSignInRecord(long playerId) {
        return querySignIn(playerId);
    }

    // ==================== 物品回收 ====================

    private static final double RECYCLE_RATE = 0.30;

    public RecycleResult recycleItem(long playerId, String itemKey, int quantity) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);

        var item = com.mtxgdn.game.item.ItemRegistry.resolve(itemKey);
        if (item == null) return RecycleResult.rejected(I18n.t("economy.item_not_found_5"));

        String fullKey = item.getFullKey();
        if (fullKey.startsWith("mtxgdn:spirit_stone")) {
            return RecycleResult.rejected(I18n.t("economy.spirit_stones_are_already_currency"));
        }
        if (!item.isTradeable()) {
            return RecycleResult.rejected(I18n.t("economy.this_item_cannot_be_traded"));
        }

        long owned = itemService.getItemCount(playerId, fullKey);
        if (owned < quantity) {
            return RecycleResult.rejected(I18n.t("economy.your_backpack", owned, item.getName()));
        }

        int basePrice = item.getPrice();
        long recycleValue = (long)(basePrice * quantity * RECYCLE_RATE);
        if (recycleValue < 1) recycleValue = 1;

        final long finalRecycleValue = recycleValue;
        DatabaseManager.runTransaction(conn -> {
            itemService.removeItem(conn, playerId, fullKey, quantity);
            itemService.addSpiritStones(conn, playerId, finalRecycleValue);
            return null;
        });

        return RecycleResult.recycled(
                I18n.t("economy.recycled_x_and_received_low", item.getName(), quantity, recycleValue),
                item.getName() + " x" + quantity, recycleValue);
    }

    // ==================== 商店 ====================

    private static final String[][] SHOP_ITEMS = {
        {"回血丹", "mtxgdn:healing_pill", "30", "恢复 50 生命值"},
        {"回蓝丹", "mtxgdn:mana_pill", "25", "恢复 30 法力值"},
        {"回灵丹", "mtxgdn:spirit_recovery_pill", "60", "恢复 100 生命 + 80 法力"},
        {"修炼丹", "mtxgdn:cultivation_elixir", "150", "获得 500 经验"},
        {"渡劫丹", "mtxgdn:tribulation_pill", "500", "突破时 +10% 成功率"},
        {"强化石", "mtxgdn:enhance_stone", "100", "强化装备所需"},
        {"灵草", "mtxgdn:spirit_grass", "20", "炼丹基本材料"},
        {"铁矿石", "mtxgdn:iron_ore", "15", "炼器基本材料"},
        {"低级肥料", "mtxgdn:low_grade_fertilizer", "20", "提高肥力25%，助力作物生长"},
        {"中级肥料", "mtxgdn:mid_grade_fertilizer", "50", "提高肥力40%，更好的收成"},
        {"高级肥料", "mtxgdn:high_grade_fertilizer", "100", "提高肥力60%，顶级收成"},
        {"杀虫剂", "mtxgdn:pesticide", "30", "清除农田病虫害"},
        {"灵草种子", "mtxgdn:spirit_grass_seed", "50", "种植灵草，60秒成熟"},
        {"千年人参种子", "mtxgdn:thousand_year_ginseng_seed", "200", "种植千年人参，180秒成熟"},
        {"暗冰草种子", "mtxgdn:dark_ice_grass_seed", "120", "种植暗冰草，90秒成熟"},
        {"火焰藤种子", "mtxgdn:fire_vine_seed", "150", "种植火焰藤，120秒成熟"},
        {"幽冥花种子", "mtxgdn:nether_flower_seed", "300", "种植幽冥花，240秒成熟（稀有）"},
        {"星草种子", "mtxgdn:star_grass_seed", "500", "种植星草，300秒成熟（史诗）"},
        {"血灵芝种子", "mtxgdn:blood_lingzhi_seed", "400", "种植血灵芝，200秒成熟（稀有）"},
        {"天山雪莲种子", "mtxgdn:tianshan_snow_lotus_seed", "1000", "种植天山雪莲，420秒成熟（传说）"},
    };

    public ShopPurchaseResult buyFromShop(long playerId, int shopIndex) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        if (shopIndex < 1 || shopIndex > SHOP_ITEMS.length) {
            return ShopPurchaseResult.rejected(I18n.t("economy.item_does_not_exist", SHOP_ITEMS.length));
        }

        String[] entry = SHOP_ITEMS[shopIndex - 1];
        String itemKey = entry[1];
        int price = Integer.parseInt(entry[2]);
        var item = com.mtxgdn.game.item.ItemRegistry.get(itemKey);

        long stones = itemService.getSpiritStoneCount(playerId);
        if (stones < price) {
            return ShopPurchaseResult.rejected(I18n.t("economy.not_enough_spirit_stones_2", price, stones));
        }

        DatabaseManager.runTransaction(conn -> {
            itemService.removeSpiritStones(conn, playerId, price);
            itemService.addItem(conn, playerId, itemKey, 1);
            return null;
        });

        String displayName = item != null ? item.getName() : itemKey;
        return ShopPurchaseResult.bought(
                I18n.t("economy.market.buy", price, displayName), displayName, price);
    }

    public String[][] getShopItems() { return SHOP_ITEMS; }

    // ==================== 灵石修炼加速 ====================

    private static final int STONES_PER_BOOST = 100;
    private static final double BOOST_MULTIPLIER = 1.5;

    public BoostResult boostCultivation(long playerId, int stonesToBurn) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);

        Player player = playerService.getPlayerById(playerId);
        if (player == null) return BoostResult.rejected(I18n.t("economy.character_not_found"));
        if (!player.isCultivating()) {
            return BoostResult.rejected(I18n.t("economy.start_cultivation_first_before_using"));
        }
        if (stonesToBurn < STONES_PER_BOOST) {
            return BoostResult.rejected(I18n.t("economy.at_least_low_grade_spirit", STONES_PER_BOOST));
        }

        long currentStones = itemService.getSpiritStoneCount(playerId);
        if (currentStones < stonesToBurn) {
            return BoostResult.rejected(I18n.t("economy.not_enough_spirit_stones_3", stonesToBurn, currentStones));
        }

        int boostHours = stonesToBurn / STONES_PER_BOOST;
        stonesToBurn = boostHours * STONES_PER_BOOST;

        int realmId = player.getRealm();
        double cultivPerSecond = com.mtxgdn.game.config.GameConfigLoader.getCultivationPerSecond(realmId);
        long boostSeconds = (long) boostHours * 3600L;
        long normalExp = (long)(cultivPerSecond * boostSeconds);
        long bonusExp = (long)(normalExp * (BOOST_MULTIPLIER - 1.0));

        final long finalStonesToBurn = stonesToBurn;
        final long finalBonusExp = bonusExp;
        DatabaseManager.runTransaction(conn -> {
            itemService.removeSpiritStones(conn, playerId, finalStonesToBurn);
            playerService.addExperience(conn, playerId, finalBonusExp);
            return null;
        });

        return BoostResult.boosted(
                I18n.t("economy.you_burn_low_grade_spirit", stonesToBurn, boostHours, BOOST_MULTIPLIER, bonusExp),
                stonesToBurn, boostHours, bonusExp);
    }

    // ==================== 金币灵石互通 ====================

    private static final long GOLD_TO_STONE_RATE = 10;
    private static final long STONE_TO_GOLD_RATE = 5;

    public CurrencyExchangeResult exchangeGoldToStones(long playerId, long goldAmount) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Player player = playerService.getPlayerById(playerId);
        if (player == null) return CurrencyExchangeResult.rejected(I18n.t("economy.character_not_found"));
        if (goldAmount < GOLD_TO_STONE_RATE) {
            return CurrencyExchangeResult.rejected(I18n.t("economy.at_least_gold_is_required", GOLD_TO_STONE_RATE));
        }
        if (player.getGold() < goldAmount) {
            return CurrencyExchangeResult.rejected(I18n.t("economy.not_enough_gold", goldAmount, player.getGold()));
        }

        long stones = goldAmount / GOLD_TO_STONE_RATE;
        DatabaseManager.runTransaction(conn -> {
            playerService.addGold(conn, playerId, -goldAmount);
            itemService.addSpiritStones(conn, playerId, stones);
            return null;
        });

        return CurrencyExchangeResult.goldToStones(
                I18n.t("economy.exchange_successful_2", goldAmount, stones), goldAmount, stones);
    }

    public CurrencyExchangeResult exchangeStonesToGold(long playerId, long stoneAmount) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        long currentStones = itemService.getSpiritStoneCount(playerId);
        if (stoneAmount < 1) {
            return CurrencyExchangeResult.rejected(I18n.t("economy.at_least_1_low_grade"));
        }
        if (currentStones < stoneAmount) {
            return CurrencyExchangeResult.rejected(I18n.t("economy.not_enough_spirit_stones", stoneAmount, currentStones));
        }

        long gold = stoneAmount * STONE_TO_GOLD_RATE;
        DatabaseManager.runTransaction(conn -> {
            itemService.removeSpiritStones(conn, playerId, stoneAmount);
            playerService.addGold(conn, playerId, gold);
            return null;
        });

        return CurrencyExchangeResult.stonesToGold(
                I18n.t("economy.exchange_successful_4", stoneAmount, gold), stoneAmount, gold);
    }

    // ==================== 灵庄 ====================

    private static final Object[][] FIXED_DEPOSIT_PLANS = {
        {7, "七日期", 4},
        {30, "月满期", 15},
        {90, "季定期", 40},
    };

    /** 活期日利率 0.2%（30 天复利约 6.2%，低于定期，保证定期产品有意义） */
    private static final double CURRENT_DAILY_RATE = 0.002;

    public BankDepositResult bankDeposit(long playerId, String type, long amount) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Player player = playerService.getPlayerById(playerId);
        if (player == null) return BankDepositResult.rejected(I18n.t("economy.character_not_found"));

        long currentStones = itemService.getSpiritStoneCount(playerId);
        if (currentStones < amount) {
            return BankDepositResult.rejected(I18n.t("economy.not_enough_spirit_stones_3", amount, currentStones));
        }
        if (amount < 100) {
            return BankDepositResult.rejected(I18n.t("economy.spirit_bank_deposits_start"));
        }

        double rate;
        int days = 0;
        String typeName;
        long maturesAt;

        if ("current".equals(type)) {
            rate = CURRENT_DAILY_RATE;
            typeName = "活期";
            maturesAt = 0;
        } else {
            int planIdx = switch (type) {
                case "fixed_7" -> 0; case "fixed_30" -> 1; case "fixed_90" -> 2;
                default -> -1;
            };
            if (planIdx < 0) {
                return BankDepositResult.rejected(I18n.t("economy.unknown_deposit_type", type));
            }

            Object[] plan = FIXED_DEPOSIT_PLANS[planIdx];
            days = (int) plan[0];
            typeName = (String) plan[1];
            rate = ((Integer) plan[2]) / 100.0;
            maturesAt = System.currentTimeMillis() + days * 86400000L;
        }

        long depositId = 0;
        try {
            depositId = DatabaseManager.runTransaction(conn -> {
                // 灵庄存款：跨等级扣除灵石（等价折算为下品计息）
                if (!itemService.removeSpiritStones(conn, playerId, amount)) {
                    throw new SQLException("灵石扣除失败");
                }

                long newDepositId = ECONOMY_DAO.insertBankDeposit(conn, playerId, type, amount, rate,
                        System.currentTimeMillis(), maturesAt);
                return newDepositId;
            });
        } catch (Exception e) {
            throw new RuntimeException("灵庄存入失败", e);
        }

        String message;
        if ("current".equals(type)) {
            message = I18n.t("economy.bank.deposit", amount,
                    String.format("%.1f", rate * 100), depositId);
        } else {
            long expectedInterest = (long)(amount * rate);
            message = I18n.t("economy.deposited_low_grade_spirit_stones",
                    amount, typeName, days, expectedInterest, depositId);
        }
        return BankDepositResult.deposited(message, amount, type, depositId);
    }

    public BankWithdrawResult bankWithdraw(long playerId, long depositId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);

        BankDeposit dep = getBankDeposit(depositId);
        if (dep == null || dep.playerId != playerId) {
            return BankWithdrawResult.rejected(I18n.t("economy.that_deposit_does_not_exist"));
        }
        if (!"active".equals(dep.status)) {
            return BankWithdrawResult.rejected(I18n.t("economy.that_deposit_has_already_been_2"));
        }

        boolean isCurrent = "current".equals(dep.depositType);
        long interest;
        String note = "";

        if (isCurrent) {
            long now = System.currentTimeMillis();
            long elapsedMs = now - dep.depositedAt;
            int elapsedDays = (int)(elapsedMs / 86400000L);
            interest = Math.max(0, (long)(dep.principal * Math.pow(1 + dep.rate, elapsedDays) - dep.principal));
        } else {
            long now = System.currentTimeMillis();
            if (dep.maturesAt > 0 && now >= dep.maturesAt) {
                interest = (long)(dep.principal * dep.rate);
                note = "（已到期，全额利息）";
            } else {
                interest = 0;
                note = "（提前取出，无利息）";
            }
        }

        try {
            DatabaseManager.runTransaction(conn -> {
                // 条件认领：仅当存款仍为 active 时才能取出，防止并发双取（本金+利息重复发放）
                if (!markBankDepositDone(conn, depositId, interest)) {
                    throw new SQLException("存款已被取出");
                }
                itemService.addItem(conn, playerId, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_LOW, dep.principal + interest);
                return null;
            });
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("存款已被取出")) {
                return BankWithdrawResult.rejected(I18n.t("economy.that_deposit_has_already_been"));
            }
            throw new RuntimeException("取款失败", e);
        }

        // note 是一个可空尾巴（提前支取时会带扣息说明），整体交给 {3}
        return BankWithdrawResult.withdrawn(
                I18n.t("economy.bank.withdraw", dep.principal, interest,
                        dep.principal + interest, note),
                dep.principal, interest, dep.principal + interest);
    }

    public BankInfo getBankInfo(long playerId) {
        List<DepositView> deposits = new ArrayList<>();
        long totalPrincipal = 0, totalPendingInterest = 0;
        long now = System.currentTimeMillis();

        // 只取原始行：利息估算与「剩余 N 天」文案依赖「现在」，留在服务层算
        for (Map<String, Object> rs : ECONOMY_DAO.findActiveBankDeposits(playerId)) {
            long id = ((Number) rs.get("id")).longValue();
            String type = (String) rs.get("deposit_type");
            long principal = ((Number) rs.get("principal")).longValue();
            double rate = ((Number) rs.get("rate")).doubleValue();
            long depositedAt = ((Number) rs.get("deposited_at")).longValue();
            long maturesAt = ((Number) rs.get("matures_at")).longValue();

            totalPrincipal += principal;

            boolean isCurrent = "current".equals(type);
            if (isCurrent) {
                int elapsedDays = (int) ((now - depositedAt) / 86400000L);
                long estimatedInterest = (long) (principal * Math.pow(1 + rate, elapsedDays) - principal);
                if (estimatedInterest < 0) estimatedInterest = 0;
                deposits.add(new DepositView(id, "活期", principal, estimatedInterest,
                        "已存 " + elapsedDays + " 天，日利 " + String.format("%.1f", rate * 100) + "%"));
                totalPendingInterest += estimatedInterest;
            } else {
                int planDays = maturesAt > 0 ? (int) ((maturesAt - depositedAt) / 86400000L) : 0;
                long remainingMs = maturesAt - now;
                long estimatedInterest = maturesAt > 0 && now >= maturesAt ? (long) (principal * rate) : 0L;
                String note;
                if (remainingMs > 0) {
                    long remainingDays = remainingMs / 86400000L + 1;
                    note = "剩余 " + remainingDays + " 天到期，利率 "
                            + String.format("%.0f", rate * 100) + "%（到期前取款无利息）";
                } else {
                    note = "已到期！可取款，利率 " + String.format("%.0f", rate * 100) + "%";
                    totalPendingInterest += (long) (principal * rate);
                }
                deposits.add(new DepositView(id, planDays + "天定期", principal, estimatedInterest, note));
            }
        }

        return new BankInfo(deposits, totalPrincipal, totalPendingInterest);
    }

    private BankDeposit getBankDeposit(long depositId) {
        // 只按 id 过滤，不带 player_id：归属校验在 bankWithdraw 里做
        Map<String, Object> row = ECONOMY_DAO.findBankDepositRow(depositId);
        if (row == null) return null;
        BankDeposit d = new BankDeposit();
        d.id = ((Number) row.get("id")).longValue();
        d.playerId = ((Number) row.get("player_id")).longValue();
        d.depositType = (String) row.get("deposit_type");
        d.principal = ((Number) row.get("principal")).longValue();
        d.rate = ((Number) row.get("rate")).doubleValue();
        d.depositedAt = ((Number) row.get("deposited_at")).longValue();
        d.maturesAt = ((Number) row.get("matures_at")).longValue();
        d.status = (String) row.get("status");
        return d;
    }

    private boolean markBankDepositDone(Connection conn, long depositId, long interest) throws SQLException {
        return ECONOMY_DAO.markBankDepositWithdrawn(conn, depositId, interest);
    }

    private static class BankDeposit {
        @SuppressWarnings("unused")
        long id;
        long playerId, principal, depositedAt, maturesAt;
        String depositType, status;
        double rate;
    }

    // ==================== 经济数据面板 ====================

    public EconomyStats getEconomyStats() {
        // 三个标量各一条语句：合并成一条 JOIN 会因为「没有物品的玩家」被丢掉而少算人数
        try {
            return EconomyStats.of(
                    ECONOMY_DAO.sumSpiritStones(),
                    ECONOMY_DAO.sumGold(),
                    ECONOMY_DAO.countPlayers(),
                    ECONOMY_DAO.countSoldTradeListings());
        } catch (Exception e) {
            return EconomyStats.failed(e.getMessage());
        }
    }

    // ==================== 竞拍行 ====================

    public AuctionResult createAuction(long playerId, String itemKey, int quantity, long startPrice, int hours) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        var item = com.mtxgdn.game.item.ItemRegistry.resolve(itemKey);
        if (item == null) return AuctionResult.fail(I18n.t("economy.item_not_found_5"));
        if (quantity <= 0 || startPrice <= 0) {
            return AuctionResult.fail(I18n.t("economy.quantity_and_starting_bid_must"));
        }

        String fullKey = item.getFullKey();
        if (!itemService.hasItem(playerId, fullKey, quantity)) {
            return AuctionResult.fail(I18n.t("economy.not_enough_in_your_backpack", item.getName()));
        }

        int realHours = Math.min(Math.max(hours, 1), 72);

        long endTimeMs = System.currentTimeMillis() + realHours * 3600000L;
        try {
            DatabaseManager.runTransaction(conn -> {
                if (!itemService.removeItem(conn, playerId, fullKey, quantity)) {
                    throw new SQLException("物品扣除失败");
                }
                ECONOMY_DAO.insertAuctionListing(conn, playerId, fullKey, quantity, startPrice,
                        new java.sql.Timestamp(endTimeMs));
                return null;
            });
        } catch (Exception e) {
            throw new RuntimeException("创建拍卖失败", e);
        }

        return AuctionResult.ok(
                I18n.t("economy.listed_on_the_auction_house", item.getName(), quantity, startPrice, realHours));
    }

    public AuctionResult placeBid(long bidderPlayerId, long listingId, long amount) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(bidderPlayerId);
        if (amount <= 0) {
            return AuctionResult.fail(I18n.t("economy.the_bid_must_be_greater"));
        }

        // 竞态修复：listing 读取与校验全部移入事务内并加锁，避免事务外快照导致
        // 前出价人被重复退款 / 新出价人灵石丢失（与坊市购买 S6 修复同一模式）
        try {
            DatabaseManager.runTransaction(conn -> {
                AuctionListing listing = getAuctionListingForUpdate(conn, listingId);
                if (listing == null) {
                    throw new SQLException("该拍卖不存在或已结束");
                }
                if (listing.sellerPlayerId == bidderPlayerId) {
                    throw new SQLException("不能竞拍自己的物品");
                }
                long minBid = listing.currentBid != null ? listing.currentBid + 1 : listing.startPrice;
                if (amount < minBid) {
                    throw new SQLException("出价必须高于当前出价");
                }
                long currentStones = itemService.getSpiritStoneCount(conn, bidderPlayerId);
                if (currentStones < amount) {
                    throw new SQLException("灵石不足，需要 " + amount + "（当前: " + currentStones + "）");
                }

                if (listing.currentBidderId != null && listing.currentBid != null) {
                    itemService.addSpiritStones(conn, listing.currentBidderId, listing.currentBid);
                }

                if (!itemService.removeSpiritStones(conn, bidderPlayerId, amount)) {
                    throw new SQLException("灵石扣除失败");
                }

                if (!ECONOMY_DAO.insertBidAndUpdateListing(conn, listingId, bidderPlayerId, amount)) {
                    throw new SQLException("拍卖已结束");
                }
                return null;
            });
        } catch (Exception e) {
            return AuctionResult.fail(I18n.t("economy.bid_failed", rootMessage(e)));
        }

        return AuctionResult.ok(I18n.t("economy.bid_placed", amount, listingId));
    }

    /** 解包 runTransaction 包装的 RuntimeException，返回业务异常的真实原因消息。 */
    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause instanceof RuntimeException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    /** 事务内读取竞拍并加行锁（MySQL 使用 FOR UPDATE；SQLite 在事务内 UPDATE 即隐式写锁）。必须在事务内调用。 */
    private AuctionListing getAuctionListingForUpdate(Connection conn, long listingId) throws SQLException {
        EconomyDAO.AuctionListingRow row = ECONOMY_DAO.findAuctionListingForUpdate(conn, listingId);
        if (row == null) return null;
        AuctionListing l = new AuctionListing();
        l.id = row.id;
        l.sellerPlayerId = row.sellerPlayerId;
        l.itemKey = row.itemKey;
        l.quantity = row.quantity;
        l.startPrice = row.startPrice;
        l.currentBid = row.currentBid;
        l.currentBidderId = row.currentBidderId;
        l.feeRate = row.feeRate;
        return l;
    }

    public List<Map<String, Object>> getActiveAuctionItems() {
        List<Map<String, Object>> result = new ArrayList<>();
        settleExpiredAuctions();
        for (Map<String, Object> rs : ECONOMY_DAO.findActiveAuctionListings()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ((Number) rs.get("id")).longValue());
            var item = com.mtxgdn.game.item.ItemRegistry.get((String) rs.get("item_key"));
            String itemKey = (String) rs.get("item_key");
            row.put("itemName", item != null ? item.getName() : itemKey);
            row.put("quantity", ((Number) rs.get("quantity")).intValue());
            row.put("startPrice", ((Number) rs.get("start_price")).longValue());
            row.put("currentBid", rs.get("current_bid"));
            row.put("remaining", estimateRemaining(toTimestamp(rs.get("end_time"))));
            result.add(row);
        }
        return result;
    }

    public List<Map<String, Object>> getPlayerAuctionItems(long playerId) {
        List<Map<String, Object>> result = new ArrayList<>();
        settleExpiredAuctions();
        for (Map<String, Object> rs : ECONOMY_DAO.findPlayerAuctionListings(playerId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ((Number) rs.get("id")).longValue());
            var item = com.mtxgdn.game.item.ItemRegistry.get((String) rs.get("item_key"));
            String itemKey = (String) rs.get("item_key");
            row.put("itemName", item != null ? item.getName() : itemKey);
            row.put("quantity", ((Number) rs.get("quantity")).intValue());
            row.put("currentBid", rs.get("current_bid"));
            row.put("status", rs.get("status"));
            result.add(row);
        }
        return result;
    }

    /**
     * DAO 交回的行里 {@code end_time} 是 {@code "ts:<毫秒>"} 字符串
     * （通用行映射为 JSON 序列化设计的），这里还原成 {@link java.sql.Timestamp}
     * 供 {@link #estimateRemaining} 用。
     */
    private static java.sql.Timestamp toTimestamp(Object value) {
        if (value instanceof java.sql.Timestamp ts) return ts;
        if (value instanceof String s && s.startsWith("ts:")) {
            return new java.sql.Timestamp(Long.parseLong(s.substring(3)));
        }
        return null;
    }

    private void settleExpiredAuctions() {
        // 「现在」只取一次：一批记录用同一个基准，不会前面的先结算、后面的晚结算
        long now = System.currentTimeMillis();
        for (EconomyDAO.AuctionSettlementRow row : ECONOMY_DAO.findActiveAuctionsForSettlement()) {
            if (row.endTime != null && row.endTime.getTime() > now) continue;
            final long listingId = row.listingId;
            final long sellerId = row.sellerPlayerId;
            final String itemKey = row.itemKey;
            final int qty = row.quantity;
            final long currentBid = row.currentBid;
            final Long bidderId = row.currentBidderId;
            final double feeRate = row.feeRate;

            DatabaseManager.runTransaction(innerConn -> {
                // 抢占：命中 0 行说明另一个请求（定时任务或另一次惰性结算）已经结过这一条
                if (!ECONOMY_DAO.markAuctionListingEnded(innerConn, listingId)) return null;

                if (bidderId != null && currentBid > 0) {
                    long fee = Math.max(1, (long)(currentBid * feeRate));
                    // 卖家收到扣除手续费后的灵石；手续费作为系统税费直接销毁（不再记入玩家 0，避免外键失败导致结算回滚）
                    itemService.addSpiritStones(innerConn, sellerId, currentBid - fee);
                    itemService.addItem(innerConn, bidderId, itemKey, qty);
                } else {
                    itemService.addItem(innerConn, sellerId, itemKey, qty);
                }
                return null;
            });
        }
        // 结算失败不影响正常查询：某一条挂单结算出错时，剩下的和查询本身都要继续
    }

    private String estimateRemaining(java.sql.Timestamp endTime) {
        if (endTime == null) return "--";
        long remaining = endTime.getTime() - System.currentTimeMillis();
        if (remaining <= 0) return "已结束";
        long hours = remaining / 3600000;
        if (hours > 0) return hours + "小时";
        long mins = remaining / 60000;
        if (mins > 0) return mins + "分钟";
        return "即将结束";
    }

    private static class AuctionListing {
        @SuppressWarnings("unused")
        long id;
        long sellerPlayerId, startPrice;
        @SuppressWarnings("unused")
        int quantity;
        @SuppressWarnings("unused")
        String itemKey;
        Long currentBid, currentBidderId;
        @SuppressWarnings("unused")
        double feeRate;
    }

    // ==================== 灵石提纯 ====================

    /** 提纯境界需求（按源等级 0/1/2）：筑基 / 元婴 / 大乘 */
    private static final int[] PURIFY_REALM = {2, 4, 7};
    /** 提纯单次消耗的洞府灵气（按源等级） */
    private static final long[] PURIFY_AURA_COST = {100, 1000, 10000};
    /** 提纯基础成功率（按源等级） */
    private static final double[] PURIFY_BASE_RATE = {0.90, 0.60, 0.30};
    /** 每次提纯消耗的源等级灵石数量（= 1 块高一级灵石的等值） */
    private static final long PURIFY_STONE_COST = 1000;

    /**
     * 提纯灵石：将低级灵石升为高级灵石。
     * 需要消耗洞府灵气与大量低阶灵石，有概率失败，且有境界需求。
     *
     * @param grade 源灵石等级（0=下品→中品，1=中品→上品，2=上品→极品）
     */
    public PurifyResult purifySpiritStone(long playerId, int grade) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            return PurifyResult.rejected(I18n.t("economy.character_not_found"));
        }
        if (grade < 0 || grade > 2) {
            return PurifyResult.rejected(I18n.t("economy.invalid_spirit_stone_grade"));
        }

        int targetGrade = grade + 1;
        String sourceName = com.mtxgdn.game.item.CurrencyEffect.GRADE_NAMES[grade];
        String targetName = com.mtxgdn.game.item.CurrencyEffect.GRADE_NAMES[targetGrade];

        // 境界需求
        if (player.getRealm() < PURIFY_REALM[grade]) {
            String realmName = GameConfigLoader.getRealmConfig(player.getRealm(), 0) != null
                    ? GameConfigLoader.getRealmConfig(player.getRealm(), 0).getFullName() : "凡人";
            String requiredName = GameConfigLoader.getRealmConfig(PURIFY_REALM[grade], 0) != null
                    ? GameConfigLoader.getRealmConfig(PURIFY_REALM[grade], 0).getFullName() : "未知境界";
            return PurifyResult.rejected(I18n.t("economy.realm_too_low", targetName, requiredName, realmName));
        }

        // 灵石数量
        String sourceKey = com.mtxgdn.game.item.CurrencyEffect.getStoneKey(grade);
        if (itemService.getItemCount(playerId, sourceKey) < PURIFY_STONE_COST) {
            return PurifyResult.rejected(I18n.t("economy.purify.short_stone", sourceName,
                    PURIFY_STONE_COST, itemService.getItemCount(playerId, sourceKey)));
        }

        // 洞府灵气（"灵气"资源）
        long auraCost = PURIFY_AURA_COST[grade];
        CaveService caveService = ServiceRegistry.getCaveService();
        Cave cave = caveService.getCaveByPlayerId(playerId);
        if (cave == null) {
            return PurifyResult.rejected(I18n.t("economy.refining_spirit_stones_requires_cave"));
        }
        if (cave.getSpiritEnergy() < auraCost) {
            return PurifyResult.rejected(I18n.t("economy.not_enough_cave_dwelling_spiritual", targetName, auraCost, cave.getSpiritEnergy()));
        }

        // 成功率：基础 + 灵力加成（上限 +15%）
        double baseRate = PURIFY_BASE_RATE[grade];
        double spiritBonus = Math.min(0.15, player.getSpirit() / 2000.0);
        double finalRate = Math.min(0.98, baseRate + spiritBonus);
        boolean success = new java.util.Random().nextDouble() < finalRate;

        final long finalAuraCost = auraCost;
        final boolean finalSuccess = success;
        final long finalStoneCost = PURIFY_STONE_COST;
        final int finalTargetGrade = targetGrade;
        DatabaseManager.runTransaction(conn -> {
            // 扣除灵石
            itemService.removeItem(conn, playerId, sourceKey, finalStoneCost);
            // 消耗洞府灵气（条件扣减：不足则整笔提纯失败）
            if (!CAVE_DAO.consumeSpiritEnergy(conn, cave.getId(), finalAuraCost)) {
                throw new SQLException("洞府灵气不足");
            }
            // 成功则发放高一级灵石
            if (finalSuccess) {
                itemService.addItem(conn, playerId, com.mtxgdn.game.item.CurrencyEffect.getStoneKey(finalTargetGrade), 1);
            }
            return null;
        });

        int ratePercent = (int) Math.round(finalRate * 100);
        String message = success
                ? I18n.t("economy.refine_successful", PURIFY_STONE_COST, sourceName, auraCost, targetName, ratePercent)
                : I18n.t("economy.refine_failed", PURIFY_STONE_COST, sourceName, auraCost, ratePercent);
        return PurifyResult.purified(message, success, grade, targetGrade,
                sourceName, targetName, auraCost, PURIFY_STONE_COST, finalRate);
    }

    /** 提纯帮助信息（供 QQ 指令展示） */
    public String getPurifyHelp(long playerId) {
        Player player = playerService.getPlayerById(playerId);
        StringBuilder sb = new StringBuilder();
        sb.append("===== 灵石提纯 =====\n");
        sb.append("提纯可消耗灵气把低级灵石升为高级灵石（有概率失败）\n\n");
        for (int grade = 0; grade < 3; grade++) {
            int targetGrade = grade + 1;
            String sourceName = com.mtxgdn.game.item.CurrencyEffect.GRADE_NAMES[grade];
            String targetName = com.mtxgdn.game.item.CurrencyEffect.GRADE_NAMES[targetGrade];
            String realmName = GameConfigLoader.getRealmConfig(PURIFY_REALM[grade], 0) != null
                    ? GameConfigLoader.getRealmConfig(PURIFY_REALM[grade], 0).getFullName() : "?";
            sb.append("· ").append(sourceName).append(" → ").append(targetName)
                    .append("  需").append(realmName).append(" · 成功率 ").append((int) (PURIFY_BASE_RATE[grade] * 100))
                    .append("% · 消耗 ").append(PURIFY_STONE_COST).append("块 + ").append(PURIFY_AURA_COST[grade]).append("灵气\n");
        }
        sb.append("\n用法: /提纯 <下品|中品|上品>");
        if (player != null) {
            sb.append("\n\n当前灵石:\n");
            for (int i = 0; i < 4; i++) {
                sb.append("  ").append(com.mtxgdn.game.item.CurrencyEffect.GRADE_NAMES[i])
                        .append(": ").append(itemService.getSpiritStoneCount(playerId, i)).append("\n");
            }
        }
        return sb.toString();
    }

    // ==================== DB helpers ====================

    private static class SignInRecord {
        String lastSignIn;
        int streak;
    }

    private SignInRecord querySignIn(long playerId) {
        // DAO 的 SignInRecord 是 db 包的类型，这里适配成服务层自己的：
        // getSignInRecord 是 public，返回类型不能变。
        // 读不到行 / 读失败都返回「从未签到」的空记录（lastSignIn = null、streak = 0）。
        EconomyDAO.SignInRecord row = ECONOMY_DAO.findSignIn(playerId);
        SignInRecord r = new SignInRecord();
        r.lastSignIn = row.getLastSignIn();
        r.streak = row.getStreak();
        return r;
    }

    private void updateSignIn(Connection conn, long playerId, String today, int streak) throws SQLException {
        ECONOMY_DAO.upsertSignIn(conn, playerId, today, streak);
    }
}