package com.mtxgdn.game.service;

import com.mtxgdn.TestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护「{@code SectService} 拆成门面 + 多个职责域」这次纯结构重构。
 *
 * <p>拆分本身不改变任何行为，所以普通的业务测试（如 {@code SectSuccessionTest}）
 * 抓不到"拆错了"这类问题 —— 它们照样全绿。本测试专门钉住拆分后的三条结构性不变量：</p>
 *
 * <ol>
 *   <li><b>门面 API 一个不少</b>：{@code SectService} 的公开成员（字段 / 构造器 / 方法 /
 *       嵌套类 {@code SuccessionOutcome}）必须与拆分前的黄金清单逐字一致。清单是从
 *       {@code javap -public} 导出后原样抄进来的，任何签名漂移都会立刻失败；</li>
 *   <li><b>门面不是空壳</b>：八个域对象必须真的被接线（字段非 null），并且门面自己
 *       不再碰任何 JDBC —— 它只转调；</li>
 *   <li><b>事务边界没被拆散</b>：{@code DatabaseManager.runTransaction} 的调用点总数
 *       与拆分前一致（9 处），并且<b>没有任何事务 lambda 内部再开连接或再开事务</b>。
 *       后者是 SQLite 下的死锁 / 读到旧快照来源，也是最容易在"搬家"时踩到的坑。</li>
 * </ol>
 *
 * <p><b>域类里为什么仍然会出现 {@code DatabaseManager.getConnection()}：</b>
 * 拆分前这个服务就是"每个方法各自开一个短连接"的手写 JDBC 写法（共 35 处），
 * 本次是纯结构重构 —— SQL 一行不改、事务边界一处不动，所以这些连接点必须原样
 * 跟着业务方法搬进域类，不能为了"好看"抽成 DAO 或包成事务。真正要禁止的是
 * <b>事务块内部</b>再开连接/再开事务（那才会死锁或读到旧快照），
 * 以及<b>新增</b>事务（那会改变原来"单语句即提交"的语义）。
 * 因此断言写成"调用点总数不变 + 事务体内干净"，而不是"域类里不许出现连接"。</p>
 *
 * <p>源码级断言一律先剥掉注释再匹配（见 {@link #stripComments}）：把关键字写在注释里
 * 不算数，也不允许注释文本冒充断言锚点。</p>
 */
class SectServiceSplitTest extends TestBase {

    /** 宗门域源码目录。 */
    private static final Path SERVICE_DIR = Paths.get("src/main/java/com/mtxgdn/game/service");

    /**
     * {@code javap -public -classpath target/classes com.mtxgdn.game.service.SectService}
     * 在<b>拆分前</b>导出的公开成员清单（逐字抄录；返回类型已随 DTO 化同步更新）。
     */
    private static final List<String> GOLDEN_FACADE_API = List.of(
            "public static final int CONTEST_ROUNDS",
            "public static final int CONTEST_WINS_NEEDED",
            "public com.mtxgdn.game.service.SectService()",
            "public static void shutdownSuccessionSweeper()",
            "public static int leaderAbsentDays()",
            "public static int contestDays()",
            "public static int contestLevelGap()",
            "public static int successionSweepSeconds()",
            "public java.util.List<com.mtxgdn.game.entity.Sect> getAllSects()",
            "public com.mtxgdn.game.entity.Sect getSectById(long)",
            "public com.mtxgdn.game.entity.Sect getSectByName(java.lang.String)",
            "public com.mtxgdn.game.entity.Sect getPlayerSect(long)",
            "public com.mtxgdn.game.sect.SectActionResult createSect(long, java.lang.String, java.lang.String)",
            "public java.util.List<com.mtxgdn.game.entity.SectMember> getSectMembers(long)",
            "public com.mtxgdn.game.entity.SectMember getMember(long, long)",
            "public com.mtxgdn.game.entity.SectMember getPlayerMember(long)",
            "public com.mtxgdn.common.result.OperationResult applyToSect(long, long, java.lang.String)",
            "public java.util.List<com.mtxgdn.game.entity.SectApplication> getPendingApplications(long)",
            "public com.mtxgdn.common.result.OperationResult approveApplication(long, long, boolean)",
            "public com.mtxgdn.common.result.OperationResult leaveSect(long)",
            "public com.mtxgdn.common.result.OperationResult kickMember(long, long)",
            "public com.mtxgdn.common.result.OperationResult appointMember(long, long, java.lang.String)",
            "public com.mtxgdn.common.result.OperationResult addContribution(long, long, long)",
            "public java.util.List<com.mtxgdn.game.entity.SectWarehouseItem> getWarehouse(long)",
            "public com.mtxgdn.game.sect.WarehouseDonateResult donateToWarehouse(long, long, java.lang.String, int)",
            "public com.mtxgdn.common.result.OperationResult withdrawFromWarehouse(long, long, java.lang.String, int)",
            "public long getItemContributionCost(com.mtxgdn.game.item.Item)",
            "public com.mtxgdn.game.sect.SectActionResult levelUp(long)",
            "public com.mtxgdn.game.sect.SectActionResult transferLeader(long, long)",
            "public com.mtxgdn.game.sect.WarResult declareWar(long, long)",
            "public com.mtxgdn.game.sect.StepDownResult stepDown(long)",
            "public com.mtxgdn.game.service.SectService$SuccessionOutcome resolveSuccession(long, long)",
            "public com.mtxgdn.game.sect.ContestResult challengeLeader(long, boolean)",
            "public java.lang.String leadershipStatus(com.mtxgdn.game.entity.Sect)",
            "public int sweepLeaderSuccession(long)",
            "public com.mtxgdn.game.sect.SectActionResult disbandSect(long)",
            "public java.util.List<com.mtxgdn.game.entity.Sect> getTopSects(int)",
            "public static int salaryIntervalHours()",
            "public static int salarySweepSeconds()",
            "public static long nextSalaryTime(com.mtxgdn.game.entity.Sect)",
            "public static long expectedSalary(com.mtxgdn.game.entity.Sect, com.mtxgdn.game.entity.SectMember)",
            "public com.mtxgdn.game.sect.TreasuryDonateResult donateStonesToTreasury(long, long)",
            "public com.mtxgdn.game.sect.TreasuryInfo getTreasuryInfo(long)",
            "public com.mtxgdn.common.result.OperationResult setSalary(long, long)",
            "public com.mtxgdn.game.sect.SalaryPayResult paySalariesNow(long)",
            "public int sweepSalaries(long)");

    /**
     * {@code javap -public com.mtxgdn.game.service.SectService$SuccessionOutcome}
     * 在拆分前导出的公开成员清单。调用方（{@code SectSuccessionTest} 等）直接引用这个
     * 嵌套类型，因此它必须留在门面里、形状不能变。
     */
    private static final List<String> GOLDEN_OUTCOME_API = List.of(
            "public final boolean succeeded",
            "public final long successorId",
            "public final java.lang.String successorName",
            "public final long rivalId",
            "public final java.lang.String rivalName",
            "public final long contestUntil",
            "public final java.lang.String note",
            "public boolean hasContest()");

    /** 门面持有的域对象字段：一个都不能漏接线，否则对应的一组方法会 NPE。 */
    private static final String[] DOMAIN_FIELDS = {
            "queryDomain", "duelDomain", "membershipDomain", "registryDomain",
            "warehouseDomain", "warDomain", "successionDomain", "treasuryDomain"
    };

    /** 拆分后 {@code DatabaseManager.runTransaction} 在宗门域里的调用点总数（拆分前也是 9 处）。 */
    private static final int EXPECTED_TRANSACTION_CALL_SITES = 9;

    // ==================== 1. 门面 API 逐字不变 ====================

    @Test
    @DisplayName("门面: 公开成员清单与拆分前逐字一致（javap 黄金清单）")
    void facadeKeepsGoldenPublicApi() {
        List<String> actual = describePublicApi(SectService.class);
        assertEquals(GOLDEN_FACADE_API.stream().sorted().toList(), actual,
                "SectService 的公开 API 必须与拆分前完全一致：多一个、少一个、"
                        + "改一个参数类型都会让 REST / WebSocket / 指令 / 插件调用方编译不过");
    }

    @Test
    @DisplayName("门面: 嵌套类 SuccessionOutcome 的形状不变")
    void nestedSuccessionOutcomeShapeIsUnchanged() {
        List<String> actual = describePublicApi(SectService.SuccessionOutcome.class);
        assertEquals(GOLDEN_OUTCOME_API.stream().sorted().toList(), actual,
                "SuccessionOutcome 的公开字段与方法被调用方直接使用，不能改名或改类型");
    }

    // ==================== 2. 门面是门面，不是空壳 ====================

    @Test
    @DisplayName("门面: 八个域对象全部接线，且转调真的能跑通")
    void facadeDelegatesToWiredDomains() throws Exception {
        SectService facade = new SectService();
        for (String name : DOMAIN_FIELDS) {
            Field field = SectService.class.getDeclaredField(name);
            field.setAccessible(true);
            Object domain = field.get(facade);
            assertNotNull(domain, "域对象 " + name + " 没有被接线：对应的一组方法会直接 NPE");
            assertTrue(domain.getClass().getSimpleName().startsWith("Sect"),
                    "门面字段 " + name + " 应该指向宗门域对象，实际是 " + domain.getClass().getName());
        }
        // 真的转调一次：门面若只剩空壳，这里会返回 null 或抛异常
        assertNotNull(facade.getAllSects(), "getAllSects 必须真的转调到查询域");
        assertNotNull(facade.getTopSects(3), "getTopSects 必须真的转调到查询域");
    }

    @Test
    @DisplayName("门面: 自己不碰 JDBC —— 连接与事务都随业务方法搬进域类")
    void facadeContainsNoJdbc() throws Exception {
        String source = stripComments(Files.readString(SERVICE_DIR.resolve("SectService.java")));
        assertFalse(source.contains("DatabaseManager.getConnection()"),
                "门面不该再自己开连接：SQL 与事务必须留在域类里");
        assertFalse(source.contains("runTransaction("),
                "门面不该再持有事务块：否则事务边界会跨类，谁在事务里就看不出来了");
        assertFalse(source.contains("java.sql"),
                "门面不该再出现 java.sql 依赖：它只做参数透传");
    }

    // ==================== 3. 事务边界没被拆散 ====================

    @Test
    @DisplayName("事务: 调用点总数与拆分前一致（9 处），没有新增/合并/丢失事务")
    void transactionCallSiteCountIsUnchanged() throws IOException {
        int total = 0;
        for (Path file : sectSources()) {
            String source = stripComments(Files.readString(file));
            total += countOccurrences(source, "DatabaseManager.runTransaction(");
        }
        assertEquals(EXPECTED_TRANSACTION_CALL_SITES, total,
                "宗门域的 runTransaction 调用点数变了 —— 这要么把两个事务并成了一个、"
                        + "要么给原本的单语句操作套了新事务，都属于改行为而不是纯结构重构");
    }

    @Test
    @DisplayName("事务: 事务 lambda 内部绝不自己开连接或再开事务")
    void transactionLambdasNeverOpenTheirOwnConnection() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : sectSources()) {
            String source = stripComments(Files.readString(file));
            for (String body : transactionLambdaBodies(source)) {
                if (body.contains("DatabaseManager.getConnection()")) {
                    offenders.add(file.getFileName() + " -> 事务块内调用了 DatabaseManager.getConnection()");
                }
                if (body.contains("runTransaction(")) {
                    offenders.add(file.getFileName() + " -> 事务块内又开了一个事务");
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "事务块内再开连接/事务在 SQLite 下会死锁或读到旧快照。"
                        + "需要复用事务时请把 Connection 作为参数传下去：\n"
                        + String.join("\n", offenders));
    }

    @Test
    @DisplayName("事务: 事务内改职位的辅助方法必须接收事务 Connection")
    void transactionHelpersTakeTheTransactionConnection() {
        List<Method> setters = Arrays.stream(SectSuccessionDomain.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("setMemberRole"))
                .toList();
        assertEquals(1, setters.size(), "setMemberRole 只应有一份实现，避免事务版与非事务版并存");
        assertEquals(Connection.class, setters.get(0).getParameterTypes()[0],
                "setMemberRole 必须以事务传入的 Connection 作为第一个参数："
                        + "继位与争夺战的落库都在同一个事务里，自己开连接会读到旧快照");
    }

    // ==================== 4. 拆分粒度 ====================

    @Test
    @DisplayName("粒度: 每个拆分后的类都在行数预算内，门面显著变薄")
    void eachSplitClassStaysUnderTheLineBudget() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : sectSources()) {
            String name = file.getFileName().toString();
            int lines = Files.readAllLines(file).size();
            int limit = name.equals("SectService.java") ? 500 : 700;
            if (lines > limit) {
                offenders.add(name + " = " + lines + " 行（上限 " + limit + "）");
            }
        }
        assertTrue(offenders.isEmpty(), "拆分粒度不足：\n" + String.join("\n", offenders));

        int facadeLines = Files.readAllLines(SERVICE_DIR.resolve("SectService.java")).size();
        assertTrue(facadeLines < 500,
                "门面应当显著变薄（拆分前 2383 行），当前 " + facadeLines + " 行");
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

    /** 宗门域（门面 + 各职责域 + 共用基础设施）的全部源码文件。 */
    private static List<Path> sectSources() throws IOException {
        try (Stream<Path> files = Files.list(SERVICE_DIR)) {
            return files.filter(p -> p.getFileName().toString().startsWith("Sect"))
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
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

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
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
}
