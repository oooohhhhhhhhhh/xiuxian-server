package com.mtxgdn.game.service;

import com.mtxgdn.common.result.OperationResult;
import com.mtxgdn.game.sect.SalaryPayResult;
import com.mtxgdn.game.sect.SalaryRow;
import com.mtxgdn.game.sect.TreasuryInfo;
import com.mtxgdn.game.sect.TreasuryDonateResult;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.SectTreasuryDAO;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.util.AppConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宗门金库与工资域：捐灵石、工资档位、周期结算、低频工资扫描。
 *
 * <p>金库是宗门里唯一<b>只进不出（对个人而言）</b>的钱袋：弟子捐进去的灵石
 * 只能通过工资按职位系数分回来，取不回本金。这条口径与仓库捐献同源 ——
 * 所以 {@link #donateStonesToTreasury} 的贡献折算（金额/10）刻意和
 * {@link SectWarehouseDomain#getItemContributionCost} 的反套利口径对齐，
 * 两处注释互相引用。</p>
 *
 * <p>方法体、SQL、日志文案、异常文案都与拆分前逐字相同。</p>
 *
 * <p><b>事务边界</b>：本类在拆分前就<b>没有</b> {@code runTransaction} —— 每一段
 * 都是一条带条件的单语句（金库入账、扣减都靠 {@code WHERE ... >= ?} 或
 * 回滚补偿来保证一致性，见 {@link #donateStonesToTreasury} 的"扣了灵石却没进金库
 * 就退回去"）。拆分后同样不引入事务：把这几段包进事务属于行为改动，
 * 不属于本次纯结构重构。</p>
 */
final class SectTreasuryDomain {

    private final ItemService itemService;
    private final SectQueryDomain queryDomain;
    private final SectMembershipDomain membershipDomain;

    /** {@code sects} 表 treasury / salary / prestige 列的读写入口（无状态，可共享）。 */
    private static final SectTreasuryDAO TREASURY_DAO = new SectTreasuryDAO();

    SectTreasuryDomain(ItemService itemService, SectQueryDomain queryDomain,
                       SectMembershipDomain membershipDomain) {
        this.itemService = itemService;
        this.queryDomain = queryDomain;
        this.membershipDomain = membershipDomain;
    }

    // ==================== 配置 ====================

    /** 工资结算周期（小时）。 */
    static int salaryIntervalHours() {
        return Math.max(1, AppConfig.getInt("sect.salary_interval_hours",
                Sect.SALARY_INTERVAL_HOURS_DEFAULT));
    }

    /** 工资扫描间隔（秒）。只是"到点了没有"的检查，不需要很密。 */
    static int salarySweepSeconds() {
        return Math.max(30, AppConfig.getInt("sect.salary_sweep_seconds", 300));
    }

    /**
     * 下次发放工资的时间戳（毫秒）；0 = 未开启工资。
     *
     * <p>{@code last_salary_time} 为 0 表示"档位刚设定、还没开始计时"，
     * 此时下次发放时间从"现在"起算一个周期 —— 否则宗主一设定档位就会被立刻发一次，
     * 而金库里的钱可能正是刚捐进来准备过几天再发的。</p>
     */
    static long nextSalaryTime(Sect sect) {
        if (sect == null || !sect.isSalaryEnabled()) {
            return 0;
        }
        long interval = salaryIntervalHours() * 3600_000L;
        long base = sect.getLastSalaryTime() > 0 ? sect.getLastSalaryTime() : System.currentTimeMillis();
        return base + interval;
    }

    /** 单个成员本周期应得工资（未考虑金库是否够）。 */
    static long expectedSalary(Sect sect, SectMember member) {
        if (sect == null || member == null || !sect.isSalaryEnabled()) {
            return 0;
        }
        double mult = Sect.getSalaryRankMultiplier(member.getRole());
        return Math.round(sect.getSalaryPerMember() * mult);
    }

    // ==================== 声望 ====================

    /** 增减宗门声望（正数增加、负数减少，下限 0）。 */
    private void addPrestige(long sectId, long amount) {
        if (amount == 0) return;
        try {
            TREASURY_DAO.addPrestige(sectId, amount);
        } catch (RuntimeException e) {
            SectSupport.LOG.warn("更新宗门声望失败: " + e.getMessage());
        }
    }

    // ==================== 金库 ====================

    /**
     * 向宗门金库捐献灵石。
     *
     * <p>与仓库捐献保持同一套反套利口径：捐进来的贡献按 <b>金额/10</b> 折算，
     * 而金库的钱只能通过工资按职位系数分发，取不回来 —— 所以"捐进去再领回来"
     * 永远亏，不存在刷贡献的路径。</p>
     */
    TreasuryDonateResult donateStonesToTreasury(long playerId, long amount) {
        if (amount <= 0) {
            return TreasuryDonateResult.fail(I18n.t("sect.treasury_bad_amount"));
        }
        Sect sect = queryDomain.getPlayerSect(playerId);
        if (sect == null) {
            return TreasuryDonateResult.fail(I18n.t("sect.not_in_sect"));
        }
        if (!itemService.removeSpiritStones(playerId, amount)) {
            return TreasuryDonateResult.fail(I18n.t("sect.treasury_no_stones", amount));
        }
        try {
            TREASURY_DAO.addTreasury(sect.getId(), amount);
        } catch (RuntimeException e) {
            // 扣了灵石却没进金库是最坏的结果，必须把灵石退回去
            itemService.addSpiritStones(playerId, amount);
            SectSupport.LOG.error("宗门金库入账失败，已退回灵石: " + e.getMessage());
            return TreasuryDonateResult.fail(I18n.t("sect.treasury_failed"));
        }

        long contribution = Math.max(1, amount / 10);
        membershipDomain.addContribution(sect.getId(), playerId, contribution);
        addPrestige(sect.getId(), Math.max(1, amount / 1000));

        long treasury = sect.getTreasury() + amount;
        return TreasuryDonateResult.donated(
                I18n.t("sect.treasury_donated", amount, contribution, treasury),
                treasury, contribution);
    }

    /** 金库与工资面板。 */
    TreasuryInfo getTreasuryInfo(long playerId) {
        Sect sect = queryDomain.getPlayerSect(playerId);
        if (sect == null) {
            return TreasuryInfo.fail(I18n.t("sect.not_in_sect"));
        }
        List<SectMember> members = queryDomain.getSectMembers(sect.getId());
        long total = 0;
        List<SalaryRow> rows = new ArrayList<>();
        for (SectMember m : members) {
            long expected = expectedSalary(sect, m);
            total += expected;
            rows.add(new SalaryRow(m.getPlayerId(), m.getPlayerName(),
                    SectMember.getRoleDisplayName(m.getRole()), expected));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(I18n.t("sect.treasury_panel_title")).append("\n");
        sb.append(I18n.t("sect.treasury_balance", sect.getTreasury())).append("\n");
        sb.append(I18n.t("sect.treasury_salary_setting", sect.getSalaryPerMember())).append("\n");
        if (sect.isSalaryEnabled()) {
            sb.append(I18n.t("sect.treasury_period_cost", total, salaryIntervalHours())).append("\n");
            long next = nextSalaryTime(sect);
            sb.append(I18n.t("sect.treasury_next_pay", SectSupport.formatTime(next))).append("\n");
            if (sect.getTreasury() < total) {
                sb.append(I18n.t("sect.treasury_short", total - sect.getTreasury())).append("\n");
            }
        } else {
            sb.append(I18n.t("sect.treasury_salary_off")).append("\n");
        }

        return TreasuryInfo.of(sb.toString(), sect, rows, total, nextSalaryTime(sect));
    }

    // ==================== 工资 ====================

    /**
     * 设定工资档位（宗主专属）。设为 0 等于关闭工资发放。
     */
    OperationResult setSalary(long playerId, long perMember) {
        if (perMember < 0 || perMember > Sect.SALARY_MAX_PER_MEMBER) {
            return OperationResult.fail(I18n.t("sect.salary_bad_amount", Sect.SALARY_MAX_PER_MEMBER));
        }
        Sect sect = queryDomain.getPlayerSect(playerId);
        if (sect == null) {
            return OperationResult.fail(I18n.t("sect.not_in_sect"));
        }
        if (sect.getLeaderPlayerId() != playerId) {
            return OperationResult.fail(I18n.t("sect.salary_leader_only"));
        }

        // 从"未开启"变为"开启"时才开始计时，避免设定瞬间就被发一次
        try {
            TREASURY_DAO.setSalaryTier(sect.getId(), perMember, System.currentTimeMillis());
        } catch (RuntimeException e) {
            SectSupport.LOG.error("设定宗门工资失败: " + e.getMessage());
            return OperationResult.fail(I18n.t("sect.salary_failed"));
        }

        return OperationResult.ok(perMember > 0
                ? I18n.t("sect.salary_set", perMember, salaryIntervalHours())
                : I18n.t("sect.salary_off"));
    }

    /** 宗主手动发放一次工资（不等周期）。 */
    SalaryPayResult paySalariesNow(long playerId) {
        Sect sect = queryDomain.getPlayerSect(playerId);
        if (sect == null) {
            return SalaryPayResult.fail(I18n.t("sect.not_in_sect"));
        }
        if (sect.getLeaderPlayerId() != playerId) {
            return SalaryPayResult.fail(I18n.t("sect.salary_leader_only"));
        }
        return paySalary(sect);
    }

    /**
     * 工资结算扫描：把到期的宗门逐个发一次。
     *
     * @return 实际发放的宗门数
     */
    int sweepSalaries(long now) {
        if (!DatabaseManager.isAvailable()) {
            return 0;
        }
        if (!com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown("sect")) {
            return 0;
        }
        long interval = salaryIntervalHours() * 3600_000L;
        int paid = 0;
        for (Sect sect : queryDomain.getAllSects()) {
            if (!sect.isSalaryEnabled()) continue;
            long last = sect.getLastSalaryTime();
            if (last <= 0) continue;              // 还没开始计时
            if (now - last < interval) continue;  // 未到发放时间
            if (paySalary(sect).success()) {
                paid++;
            }
        }
        return paid;
    }

    /**
     * 真正的发放逻辑：按职位系数算出每人应得，再按金库余额等比缩放。
     *
     * <p>金库不足时<b>按比例发</b>而不是"先到先得"：后者会让在线早的成员拿走全部，
     * 离线成员一分没有，纯看运气；按比例至少人人有份、且发放总额绝不会超过金库余额，
     * 系统不会凭空产出灵石。</p>
     */
    private SalaryPayResult paySalary(Sect sect) {
        if (!sect.isSalaryEnabled()) {
            return SalaryPayResult.fail(I18n.t("sect.salary_off"));
        }
        List<SectMember> members = queryDomain.getSectMembers(sect.getId());
        if (members.isEmpty()) {
            return SalaryPayResult.fail(I18n.t("sect.salary_no_members"));
        }

        Map<Long, Long> expected = new LinkedHashMap<>();
        long total = 0;
        for (SectMember m : members) {
            long e = expectedSalary(sect, m);
            if (e <= 0) continue;
            expected.put(m.getPlayerId(), e);
            total += e;
        }
        if (total <= 0) {
            return SalaryPayResult.fail(I18n.t("sect.salary_off"));
        }

        long available = Math.max(0, sect.getTreasury());
        boolean full = available >= total;
        double ratio = full ? 1.0 : (double) available / (double) total;

        Map<Long, Long> payout = new LinkedHashMap<>();
        long paidTotal = 0;
        for (Map.Entry<Long, Long> e : expected.entrySet()) {
            long amount = full ? e.getValue() : (long) Math.floor(e.getValue() * ratio);
            if (amount <= 0) continue;
            payout.put(e.getKey(), amount);
            paidTotal += amount;
        }
        if (paidTotal <= 0) {
            // 金库是 0：不发钱，但仍然推进周期，避免每次扫描都重复尝试
            markSalaryPaid(sect.getId(), System.currentTimeMillis());
            return SalaryPayResult.fail(I18n.t("sect.salary_empty_treasury"));
        }

        // 先扣金库（条件更新防并发重复扣），扣成功才发钱
        try {
            if (!TREASURY_DAO.deductTreasuryIfEnough(sect.getId(), paidTotal, System.currentTimeMillis())) {
                // 被并发任务抢先扣过了，这一轮直接放弃，等下个周期
                return SalaryPayResult.fail(I18n.t("sect.salary_failed"));
            }
        } catch (RuntimeException e) {
            SectSupport.LOG.error("宗门工资扣减金库失败: " + e.getMessage());
            return SalaryPayResult.fail(I18n.t("sect.salary_failed"));
        }

        int paidCount = 0;
        for (Map.Entry<Long, Long> e : payout.entrySet()) {
            try {
                if (itemService.addSpiritStones(e.getKey(), e.getValue())) {
                    paidCount++;
                }
            } catch (Exception ex) {
                SectSupport.LOG.warn("宗门工资发放失败 playerId=" + e.getKey() + ": " + ex.getMessage());
            }
        }

        return SalaryPayResult.paid(
                full ? I18n.t("sect.salary_paid", paidCount, paidTotal)
                     : I18n.t("sect.salary_paid_scaled", paidCount, paidTotal, total),
                paidCount, paidTotal, !full);
    }

    private void markSalaryPaid(long sectId, long now) {
        try {
            TREASURY_DAO.markSalaryPaid(sectId, now);
        } catch (RuntimeException e) {
            SectSupport.LOG.warn("更新宗门工资结算时间失败: " + e.getMessage());
        }
    }
}
