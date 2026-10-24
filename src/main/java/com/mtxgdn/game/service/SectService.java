package com.mtxgdn.game.service;

import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectApplication;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.game.sect.SectOperationResult;
import com.mtxgdn.game.entity.SectWarehouseItem;
import com.mtxgdn.game.item.Item;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 宗门服务门面。
 *
 * <p><b>为什么有这个类</b>：原来它是 2383 行的上帝类，把六件互不相干的事塞在一起 ——
 * 宗门建档、成员与申请、仓库捐献、宗门战、宗主继位/竞争期、金库工资。
 * 现按"职责域 + 依赖集合"拆成若干个 {@code Sect*Domain} 协作类，
 * <b>本类保留为门面</b>：方法签名、返回类型、异常行为、返回 {@code Map} 的 key 与语义
 * 逐字不变，内部转调对应的域类。</p>
 *
 * <p><b>为什么坚持门面而不是让调用方直接找域类</b>：{@code SectService} 的调用方有
 * REST（{@code GameSectResource}）、WebSocket（{@code WsSectHandler}）、
 * QQ 指令（{@code SectCommand}）、插件（{@code PluginContext}）与测试，
 * 共 40 余处调用点。纯结构重构不应该让这些调用点跟着改 —— 门面把"谁实现"挡在内部，
 * 调用方一行都不用动。</p>
 *
 * <p><b>拆分后的职责划分</b>：</p>
 * <ul>
 *   <li>{@link SectQueryDomain}：宗门与成员的只读查询、宗门排行（被其它域共用）；</li>
 *   <li>{@link SectRegistryDomain}：创建 / 解散 / 升级 / 宗主转让；</li>
 *   <li>{@link SectMembershipDomain}：入宗申请与审批、退宗、踢人、任命、贡献值；</li>
 *   <li>{@link SectWarehouseDomain}：仓库捐献 / 取出与贡献值定价；</li>
 *   <li>{@link SectDuelDomain}：纯内存对拼规则（宗门战与宗主争夺战共用）；</li>
 *   <li>{@link SectWarDomain}：宗门战流程与战报落库；</li>
 *   <li>{@link SectSuccessionDomain}：宗主缺位继位、竞争期、低频扫描；</li>
 *   <li>{@link SectTreasuryDomain}：宗门金库与工资结算；</li>
 *   <li>{@link SectSupport}：跨域共用的日志、实体映射与时间格式化。</li>
 * </ul>
 *
 * <p><b>依赖怎么来</b>：{@code playerService} / {@code itemService} 仍由本类从
 * {@link ServiceRegistry} 取一份（与拆分前完全一致），再通过构造函数注入给各域类 ——
 * 整个宗门域因此看到的是同一批服务实例，且"某个域依赖谁"在构造函数上就能读出来。
 * 域类之间不互相 {@code new}，全部由本类在构造时接线。</p>
 *
 * <p><b>事务边界</b>：本类不含任何 JDBC 调用，事务块（{@code runTransaction}）
 * 随其所属的业务方法一起搬进了对应域类，一个都没有拆开、也没有合并；
 * 每个事务块内只使用事务传入的 {@code Connection}，不会在事务里再开连接
 * （SQLite 下那会直接死锁或读到旧快照）。拆分前后 {@code runTransaction}
 * 的调用点数量与位置一一对应。</p>
 *
 * <p><b>两个静态调度任务留在本类</b>：继位扫描与工资结算的定时器属于"服务生命周期"，
 * 而不是某个业务域 —— 它要调的是本门面的公开方法，放这里才不会被误当成域内逻辑。
 * 首个 tick 的延迟与间隔、守护线程名、异常吞掉的方式都与拆分前逐字相同。</p>
 */
public class SectService {

    /** 宗主争夺战局数（三局两胜）。 */
    public static final int CONTEST_ROUNDS = 3;

    /** 挑战者需要赢下的局数。 */
    public static final int CONTEST_WINS_NEEDED = 2;

    private final SectQueryDomain queryDomain;
    private final SectDuelDomain duelDomain;
    private final SectMembershipDomain membershipDomain;
    private final SectRegistryDomain registryDomain;
    private final SectWarehouseDomain warehouseDomain;
    private final SectWarDomain warDomain;
    private final SectSuccessionDomain successionDomain;
    private final SectTreasuryDomain treasuryDomain;

    /**
     * 继位扫描调度器。
     * <p>只负责两件低频维护：把"长期不活跃的宗主"标记为缺位、清理到期的竞争期。
     * 主动退位走的是同步路径，不依赖这个任务。</p>
     */
    private static final ScheduledExecutorService successionScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sect-succession-sweeper");
                t.setDaemon(true); // 守护线程：不阻止 JVM 退出
                return t;
            });

    static {
        // 首个 tick 延迟 45s（等数据库与服务注册表就绪），之后按配置间隔扫一次。
        successionScheduler.scheduleAtFixedRate(() -> {
            try {
                ServiceRegistry.getSectService().sweepLeaderSuccession(System.currentTimeMillis());
            } catch (Throwable t) {
                // 绝不让异常冒泡：一次失败不应终止后续所有 tick
                SectSupport.LOG.warn("宗门宗主继位扫描任务异常: " + t);
            }
        }, 45, successionSweepSeconds(), TimeUnit.SECONDS);

        // 工资结算：与继位扫描共用同一个单线程调度器（都是低频 DB 操作，串行反而省连接）
        successionScheduler.scheduleAtFixedRate(() -> {
            try {
                ServiceRegistry.getSectService().sweepSalaries(System.currentTimeMillis());
            } catch (Throwable t) {
                SectSupport.LOG.warn("宗门工资结算任务异常: " + t);
            }
        }, 60, salarySweepSeconds(), TimeUnit.SECONDS);
    }

    public SectService() {
        PlayerService playerService = ServiceRegistry.getPlayerService();
        ItemService itemService = ServiceRegistry.getItemService();

        this.queryDomain = new SectQueryDomain();
        this.duelDomain = new SectDuelDomain();
        this.membershipDomain = new SectMembershipDomain(playerService, queryDomain);
        this.registryDomain = new SectRegistryDomain(playerService, itemService, queryDomain);
        this.warehouseDomain = new SectWarehouseDomain(itemService, queryDomain);
        this.warDomain = new SectWarDomain(playerService, itemService, queryDomain, duelDomain);
        this.successionDomain = new SectSuccessionDomain(playerService, queryDomain, duelDomain);
        this.treasuryDomain = new SectTreasuryDomain(itemService, queryDomain, membershipDomain);
    }

    /** 停止继位扫描任务（服务端关闭 / 测试清理时调用）。 */
    public static void shutdownSuccessionSweeper() {
        successionScheduler.shutdown();
    }

    /** 宗主连续不活跃多少天判定弃坑；0 = 关闭该判定（只保留主动退位路径）。 */
    public static int leaderAbsentDays() {
        return SectSuccessionDomain.leaderAbsentDays();
    }

    /** 竞争期持续天数。 */
    public static int contestDays() {
        return SectSuccessionDomain.contestDays();
    }

    /** 竞争期开启的境界序位差门槛：小于该值才开竞争期。 */
    public static int contestLevelGap() {
        return SectSuccessionDomain.contestLevelGap();
    }

    /** 继位扫描间隔（秒），最小 30 秒。 */
    public static int successionSweepSeconds() {
        return SectSuccessionDomain.successionSweepSeconds();
    }

    /** 工资结算周期（小时）。 */
    public static int salaryIntervalHours() {
        return SectTreasuryDomain.salaryIntervalHours();
    }

    /** 工资扫描间隔（秒）。只是"到点了没有"的检查，不需要很密。 */
    public static int salarySweepSeconds() {
        return SectTreasuryDomain.salarySweepSeconds();
    }

    /** 下次发放工资的时间戳（毫秒）；0 = 未开启工资。 */
    public static long nextSalaryTime(Sect sect) {
        return SectTreasuryDomain.nextSalaryTime(sect);
    }

    /** 单个成员本周期应得工资（未考虑金库是否够）。 */
    public static long expectedSalary(Sect sect, SectMember member) {
        return SectTreasuryDomain.expectedSalary(sect, member);
    }

    // ==================== 宗门查询（委托 SectQueryDomain） ====================

    public List<Sect> getAllSects() {
        return queryDomain.getAllSects();
    }

    public Sect getSectById(long sectId) {
        return queryDomain.getSectById(sectId);
    }

    public Sect getSectByName(String name) {
        return queryDomain.getSectByName(name);
    }

    public Sect getPlayerSect(long playerId) {
        return queryDomain.getPlayerSect(playerId);
    }

    /** 宗门排行（按声望倒序取前 {@code limit} 个）。 */
    public List<Sect> getTopSects(int limit) {
        return queryDomain.getTopSects(limit);
    }

    public List<SectMember> getSectMembers(long sectId) {
        return queryDomain.getSectMembers(sectId);
    }

    public SectMember getMember(long sectId, long playerId) {
        return queryDomain.getMember(sectId, playerId);
    }

    public SectMember getPlayerMember(long playerId) {
        return queryDomain.getPlayerMember(playerId);
    }

    // ==================== 宗门建档（委托 SectRegistryDomain） ====================

    public Map<String, Object> createSect(long playerId, String sectName, String description) {
        return registryDomain.createSect(playerId, sectName, description);
    }

    public Map<String, Object> levelUp(long playerId) {
        return registryDomain.levelUp(playerId);
    }

    public Map<String, Object> transferLeader(long fromPlayerId, long toPlayerId) {
        return registryDomain.transferLeader(fromPlayerId, toPlayerId);
    }

    public Map<String, Object> disbandSect(long playerId) {
        return registryDomain.disbandSect(playerId);
    }

    // ==================== 成员与申请（委托 SectMembershipDomain） ====================

    public SectOperationResult applyToSect(long playerId, long sectId, String message) {
        return membershipDomain.applyToSect(playerId, sectId, message);
    }

    public List<SectApplication> getPendingApplications(long sectId) {
        return membershipDomain.getPendingApplications(sectId);
    }

    public SectOperationResult approveApplication(long approverPlayerId, long applicationId, boolean approved) {
        return membershipDomain.approveApplication(approverPlayerId, applicationId, approved);
    }

    public SectOperationResult leaveSect(long playerId) {
        return membershipDomain.leaveSect(playerId);
    }

    public SectOperationResult kickMember(long kickerPlayerId, long targetPlayerId) {
        return membershipDomain.kickMember(kickerPlayerId, targetPlayerId);
    }

    public SectOperationResult appointMember(long appointerPlayerId, long targetPlayerId, String newRole) {
        return membershipDomain.appointMember(appointerPlayerId, targetPlayerId, newRole);
    }

    public SectOperationResult addContribution(long sectId, long playerId, long amount) {
        return membershipDomain.addContribution(sectId, playerId, amount);
    }

    // ==================== 仓库（委托 SectWarehouseDomain） ====================

    public List<SectWarehouseItem> getWarehouse(long sectId) {
        return warehouseDomain.getWarehouse(sectId);
    }

    public Map<String, Object> donateToWarehouse(long playerId, long sectId, String itemKey, int quantity) {
        return warehouseDomain.donateToWarehouse(playerId, sectId, itemKey, quantity);
    }

    public Map<String, Object> withdrawFromWarehouse(long playerId, long sectId, String itemKey, int quantity) {
        return warehouseDomain.withdrawFromWarehouse(playerId, sectId, itemKey, quantity);
    }

    /**
     * 物品兑换贡献值（取价格/5，最低10）。
     * 高于捐献所得（价格/10），保证"捐献→取出"循环总是亏损，杜绝套利。
     */
    public long getItemContributionCost(Item item) {
        return warehouseDomain.getItemContributionCost(item);
    }

    // ==================== 宗门战（委托 SectWarDomain） ====================

    public Map<String, Object> declareWar(long attackerPlayerId, long defenderSectId) {
        return warDomain.declareWar(attackerPlayerId, defenderSectId);
    }

    // ==================== 宗主继位 / 竞争期（委托 SectSuccessionDomain） ====================

    /**
     * 继位结果。
     * <p>{@link #succeeded} 为 false 时 {@link #note} 说明原因（宗主在任 / 竞争期进行中 /
     * 无可继位成员 / 状态被并发推进），调用方直接把它转成玩家提示即可。</p>
     */
    public static class SuccessionOutcome {
        public final boolean succeeded;
        public final long successorId;
        public final String successorName;
        public final long rivalId;
        public final String rivalName;
        /** >0 表示同时开启了竞争期，值为截止时间戳。 */
        public final long contestUntil;
        public final String note;

        private SuccessionOutcome(boolean succeeded, long successorId, String successorName,
                                  long rivalId, String rivalName, long contestUntil, String note) {
            this.succeeded = succeeded;
            this.successorId = successorId;
            this.successorName = successorName;
            this.rivalId = rivalId;
            this.rivalName = rivalName;
            this.contestUntil = contestUntil;
            this.note = note;
        }

        static SuccessionOutcome none(String note) {
            return new SuccessionOutcome(false, 0, null, 0, null, 0, note);
        }

        static SuccessionOutcome success(long successorId, String successorName,
                                         long rivalId, String rivalName, long contestUntil) {
            return new SuccessionOutcome(true, successorId, successorName, rivalId, rivalName, contestUntil, null);
        }

        public boolean hasContest() {
            return contestUntil > 0;
        }
    }

    /**
     * 宗主主动退位：卸任但仍留在宗门（降为副宗主），随后立刻按贡献选出继任者。
     *
     * <p>这是打破"宗主弃坑 → 宗门死锁"的关键入口：改造前宗主<b>既不能退位也不能退宗</b>
     * （{@link #leaveSect} 直接拒绝），一旦宗主不玩了，这个宗门就永远没人能接手。
     * 现在宗主可以先 {@code /宗门 退位}，再正常 {@code /宗门 退出}。</p>
     */
    public Map<String, Object> stepDown(long playerId) {
        return successionDomain.stepDown(playerId);
    }

    /**
     * 按贡献继位：为缺位的宗门选出新宗主，并在继任者与最强竞争者实力接近时开启竞争期。
     *
     * <p>三条不变量：</p>
     * <ol>
     *   <li><b>幂等</b>：{@code leader_absent_since = 0} 或竞争期未结束时直接返回，不重复换人；</li>
     *   <li><b>并发安全</b>：落库用条件更新（{@code WHERE leader_absent_since = 读到的值}），
     *       指令与扫描任务同时触发时只有一个生效；</li>
     *   <li><b>现任宗主不是候选人</b>：被替换的人不会再被"选"回来 —— 否则退位等于没退。</li>
     * </ol>
     *
     * @param now 统一由调用方传入，便于测试注入时间（不要在方法内部取系统时间）
     */
    public SuccessionOutcome resolveSuccession(long sectId, long now) {
        return successionDomain.resolveSuccession(sectId, now);
    }

    /**
     * 竞争期操作入口：挑战者发起、宗主查看状态、宗主应战。
     *
     * <p>两段式设计（而不是挑战者一发起就打）是为了给宗主一个明确的应战入口，
     * 也让"发起但宗主不应战"成为一种可记录的状态 —— 到期后宗主保住位置。</p>
     *
     * @param accept true = 宗主应战；false = 挑战者发起 / 宗主查看竞争期状态
     */
    public Map<String, Object> challengeLeader(long playerId, boolean accept) {
        return successionDomain.challengeLeader(playerId, accept);
    }

    /**
     * 竞争期 / 宗主缺位状态的一行式描述，供 {@code /宗门 info} 展示。
     *
     * @return 无竞争期且宗主在任时返回 {@code null}（调用方据此决定是否追加这一行）
     */
    public String leadershipStatus(Sect sect) {
        return successionDomain.leadershipStatus(sect);
    }

    /**
     * 低频维护扫描：清理到期竞争期 → 标记不活跃宗主为缺位 → 执行继位。
     *
     * @param now 统一由调用方传入（测试可注入任意时间）
     * @return 本次实际处理（换宗主 / 标记缺位 / 清理竞争期）的宗门数量
     */
    public int sweepLeaderSuccession(long now) {
        return successionDomain.sweepLeaderSuccession(now);
    }

    // ==================== 金库与工资（委托 SectTreasuryDomain） ====================

    /**
     * 向宗门金库捐献灵石。
     *
     * <p>与仓库捐献保持同一套反套利口径：捐进来的贡献按 <b>金额/10</b> 折算，
     * 而金库的钱只能通过工资按职位系数分发，取不回来 —— 所以"捐进去再领回来"
     * 永远亏，不存在刷贡献的路径。</p>
     */
    public Map<String, Object> donateStonesToTreasury(long playerId, long amount) {
        return treasuryDomain.donateStonesToTreasury(playerId, amount);
    }

    /** 金库与工资面板。 */
    public Map<String, Object> getTreasuryInfo(long playerId) {
        return treasuryDomain.getTreasuryInfo(playerId);
    }

    /**
     * 设定工资档位（宗主专属）。设为 0 等于关闭工资发放。
     */
    public Map<String, Object> setSalary(long playerId, long perMember) {
        return treasuryDomain.setSalary(playerId, perMember);
    }

    /** 宗主手动发放一次工资（不等周期）。 */
    public Map<String, Object> paySalariesNow(long playerId) {
        return treasuryDomain.paySalariesNow(playerId);
    }

    /**
     * 工资结算扫描：把到期的宗门逐个发一次。
     *
     * @return 实际发放的宗门数
     */
    public int sweepSalaries(long now) {
        return treasuryDomain.sweepSalaries(now);
    }
}
