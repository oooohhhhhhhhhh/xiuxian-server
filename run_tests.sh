#!/usr/bin/env bash
#
# 运行完整测试套件（全部测试；具体数量以实际输出为准）
#
# 历史遗留问题：本脚本原先引用 -Dtest=SecurityTest 与
# -Dtest=ComprehensiveFunctionalTest，这两个类在仓库里并不存在，
# 直接跑必然失败。现改为运行全部测试，并修正 wrapper 探测路径。
#
# 用法:
#   ./run_tests.sh              跑全部测试
#   ./run_tests.sh -Dtest=Foo   透传参数给 Maven

set -o pipefail

cd "$(dirname "$0")" || exit 1

echo "================================================================================"
echo "修仙服务器测试套件"
echo "================================================================================"
echo ""

# ---- 选择 Maven：优先项目自带 wrapper，否则用全局 mvn ----
if [ -x "./mvnw" ]; then
    MVN="./mvnw"
    echo "[i] 使用项目自带 Maven wrapper: ./mvnw"
elif command -v mvn >/dev/null 2>&1; then
    MVN="mvn"
    echo "[i] 使用全局 Maven: $(command -v mvn)"
else
    echo "✗ 既找不到 ./mvnw，也找不到全局 mvn"
    echo "  请安装 Maven 3.8+，或确认项目根目录存在 mvnw / mvnw.cmd"
    exit 1
fi
echo ""

# ---- 低内存机器上的堆设置（构建期默认值偏保守，避免 OOM 打崩 JVM）----
# 本仓库历史上多次出现 hs_err「out of physical RAM or swap space」崩溃，
# 这里给出可控的默认值；外部已设置 MAVEN_OPTS 时不覆盖。
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx384m -XX:MaxMetaspaceSize=256m -Xss1m -XX:+UseSerialGC}"

echo "[1/2] 清理旧的测试数据库..."
rm -f test_combat.db test_combat.db-shm test_combat.db-wal
rm -f test_full.db   test_full.db-shm   test_full.db-wal
rm -f xiuxian_test.db xiuxian_test.db-shm xiuxian_test.db-wal
echo "✓ 清理完成"
echo ""

echo "[2/2] 编译并运行全部测试..."
echo "----------------------------------------"
"$MVN" clean test "$@"
STATUS=$?
echo "----------------------------------------"
echo ""

if [ $STATUS -ne 0 ]; then
    echo "✗ 测试失败（退出码 $STATUS）"
    echo "  详细报告: target/surefire-reports/"
    exit $STATUS
fi

echo "================================================================================"
echo "✓ 测试全部通过"
echo "================================================================================"
