package com.mtxgdn.game.service;

import com.mtxgdn.common.result.OperationResult;
import com.mtxgdn.game.sect.WarehouseDonateResult;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.SectWarehouseDAO;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.game.entity.SectWarehouseItem;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.i18n.I18n;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 宗门仓库域：捐献、取出、列表，以及"物品折多少贡献值"的定价。
 *
 * <p>仓库是宗门里唯一<b>双向</b>流动物品的地方（进：弟子捐献；出：弟子取用），
 * 因此它自己就带一整套反套利口径 —— 捐进来的贡献按物品价格/10 折算，取出去
 * 按价格/5 计费（{@link #getItemContributionCost}），并且管理职位捐献不再发贡献值
 * （其特权已是免费取物）。这几条互相咬合，必须待在同一个类里才看得懂，
 * 所以它们一起从 {@code SectService} 搬到了这里。</p>
 *
 * <p>方法体、SQL、异常文案、{@code Map} 里 key 的顺序都与拆分前逐字相同。
 * 特别保留了两处方言的 {@code ON CONFLICT / ON DUPLICATE KEY} 分支选择。</p>
 *
 * <p><b>事务边界</b>：捐献（扣背包 + 入仓库 + 加贡献 + 加声望）与取出
 * （扣贡献 + 减仓库 + 发背包）原本就各是一个 {@code runTransaction}，
 * 拆分时两个事务块整体搬了过来，块内只使用事务传入的 {@code conn}
 * （背包增减走 {@code itemService} 的 {@code conn} 重载），不会在事务里再开连接。
 * {@link #getItemContributionCost} 是纯函数（只读 {@code Item} 对象），
 * 因此可以安全地在事务块内被调用 —— 这是它必须保持纯函数的原因。</p>
 */
final class SectWarehouseDomain {

    private final ItemService itemService;
    private final SectQueryDomain queryDomain;

    /** {@code sect_warehouse} 的读写入口（无状态，可共享）。 */
    private static final SectWarehouseDAO WAREHOUSE_DAO = new SectWarehouseDAO();

    SectWarehouseDomain(ItemService itemService, SectQueryDomain queryDomain) {
        this.itemService = itemService;
        this.queryDomain = queryDomain;
    }

    // ==================== 仓库系统 ====================

    List<SectWarehouseItem> getWarehouse(long sectId) {
        // 异常文案与 SQL 一起搬进了 DAO：DAO 的自取连接版抛的就是这条。
        // 行 → 实体的映射仍在这里：每列"老库缺列就跳过"的容错是域规则
        List<SectWarehouseItem> result = new ArrayList<>();
        for (SectWarehouseDAO.WarehouseItemRow row : WAREHOUSE_DAO.getWarehouse(sectId)) {
            SectWarehouseItem i = new SectWarehouseItem();
            i.setId(row.id());
            i.setSectId(row.sectId());
            i.setItemKey(row.itemKey());
            i.setQuantity(row.quantity());
            i.setCreatedAt(row.createdAt());
            i.setDonatedByPlayerId(row.donatedByPlayerId());
            i.setDonatedByName(row.donatedByName());
            result.add(i);
        }
        return result;
    }

    WarehouseDonateResult donateToWarehouse(long playerId, long sectId, String itemKey, int quantity) {
        SectMember member = queryDomain.getMember(sectId, playerId);
        if (member == null) {
            return WarehouseDonateResult.fail(I18n.t("sect.you_are_not_a_member"));
        }

        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            return WarehouseDonateResult.fail(I18n.t("sect.item_does_not_exist"));
        }
        String fullKey = item.getFullKey();
        if (quantity <= 0) {
            return WarehouseDonateResult.fail(I18n.t("sect.the_amount_must_be_greater"));
        }
        if (!itemService.hasItem(playerId, fullKey, quantity)) {
            return WarehouseDonateResult.fail(I18n.t("sect.not_enough_in_your_backpack", item.getName()));
        }

        // 修复刷贡献 bug：管理职位（宗主/副宗主/长老）可以免费从仓库取出物品，
        // 若捐献时仍发放贡献值，则取出→捐献→取出无限循环即可刷取海量贡献值。
        // 因此管理职位捐献不再获得贡献值（其特权已是免费取物），仅普通弟子捐献获得贡献值。
        // 贡献值按物品价格折算（约为取出成本的 1/3~1/2），保证"捐低价物换高价物"无利可图。
        final boolean isManager = member.canManage();
        final long contributionGain = isManager ? 0L : quantity * Math.max(2L, item.getPrice() / 10L);

        DatabaseManager.runTransaction(conn -> {
            itemService.removeItem(conn, playerId, fullKey, quantity);
            WAREHOUSE_DAO.upsertWarehouse(conn, sectId, fullKey, quantity, playerId);
            // 仅普通弟子捐献发放贡献值
            if (contributionGain > 0) {
                WAREHOUSE_DAO.addContribution(conn, sectId, playerId, contributionGain);
            }
            // 任何成员捐献都小幅增加宗门声望（声望是宗门升级/宗门战的资源，此前无来源导致死锁）
            long prestigeGain = Math.max(1L, quantity / 10L);
            WAREHOUSE_DAO.addPrestige(conn, sectId, prestigeGain);
            return null;
        });

        String message = isManager
                ? I18n.t("sect.donated_x_to_the_sect", item.getName(), quantity)
                : I18n.t("sect.donated_x_to_the_sect_2", item.getName(), quantity, contributionGain);
        return WarehouseDonateResult.donated(message, contributionGain);
    }

    OperationResult withdrawFromWarehouse(long playerId, long sectId, String itemKey, int quantity) {
        SectMember member = queryDomain.getMember(sectId, playerId);
        if (member == null) {
            return OperationResult.fail(I18n.t("sect.you_are_not_a_member"));
        }
        if (quantity <= 0) {
            return OperationResult.fail(I18n.t("sect.the_amount_must_be_greater"));
        }

        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            return OperationResult.fail(I18n.t("sect.item_does_not_exist"));
        }
        String fullKey = item.getFullKey();

        int available = getWarehouseItemCount(sectId, fullKey);
        if (available < quantity) {
            return OperationResult.fail(
                    I18n.t("sect.not_enough_in_the_sect", item.getName(), available));
        }

        boolean isManager = member.canManage();
        if (!isManager) {
            long costPerItem = getItemContributionCost(item);
            long totalCost = costPerItem * quantity;
            if (member.getContribution() < totalCost) {
                return OperationResult.fail(I18n.t("sect.not_enough_contribution", item.getName(), quantity, totalCost, member.getContribution()));
            }
        }

        // 单事务 + 条件扣除：贡献值、仓库数量、背包发放原子完成，防止并发超取/重复取物
        try {
            DatabaseManager.runTransaction(conn -> {
                if (!isManager) {
                    long totalCost = getItemContributionCost(item) * quantity;
                    if (!WAREHOUSE_DAO.deductContributionIfEnough(conn, member.getId(), totalCost)) {
                        throw new SQLException("贡献值不足");
                    }
                }

                if (!WAREHOUSE_DAO.deductWarehouseIfEnough(conn, sectId, fullKey, quantity)) {
                    throw new SQLException("仓库物品数量不足");
                }

                if (!itemService.addItem(conn, playerId, fullKey, quantity)) {
                    throw new SQLException("发放物品失败");
                }
                return null;
            });
        } catch (Exception e) {
            // "未知错误" 保留在代码里当兜底，不走词条：
            // 它只在异常消息为空时出现，属于"连系统都说不清"的例外情况
            return OperationResult.fail(I18n.t("sect.withdraw.failed",
                    e.getMessage() != null ? e.getMessage() : "未知错误"));
        }

        String msg;
        if (isManager) {
            msg = "已从宗门仓库取出【" + item.getName() + "】×" + quantity;
        } else {
            long cost = getItemContributionCost(item) * quantity;
            msg = "已从宗门仓库取出【" + item.getName() + "】×" + quantity + "，消耗贡献值 " + cost;
        }
        return OperationResult.ok(msg);
    }

    /**
     * 物品兑换贡献值（取价格/5，最低10）。
     * 高于捐献所得（价格/10），保证"捐献→取出"循环总是亏损，杜绝套利。
     */
    long getItemContributionCost(Item item) {
        int price = item.getPrice();
        return Math.max(10, price / 5);
    }

    private int getWarehouseItemCount(long sectId, String itemKey) {
        try {
            return WAREHOUSE_DAO.getWarehouseItemCount(sectId, itemKey);
        } catch (RuntimeException e) {
            // 查不到存量按 0 处理：调用方紧接着就会用「存量 < 数量」给出失败文案
            return 0;
        }
    }
}
