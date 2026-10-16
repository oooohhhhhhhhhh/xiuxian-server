#!/usr/bin/env bash
# ======================================
#  修仙世界 - Linux / macOS 启动脚本
# ======================================
#  用法:
#    ./start.sh [JVM参数] [--nogui|--demo]
#    ./start.sh /path/to/main-*.jar --nogui    # 指定 jar
#    XIUXIAN_MEM=-Xmx256m ./start.sh           # 低内存模式（默认 512m）
#    JAVA_OPTS="..." ./start.sh                # 自定义 JVM 参数
# ======================================
set -e
cd "$(dirname "$0")"

# 1. 定位可执行 jar（排除 original-* 未 shade 的原始包）
#    刻意不写死带版本号的文件名：版本号一改，写死的路径就会失效，
#    这里统一用通配匹配，与 pom.xml 的 <version> 解耦。
JAR=""
if [ -n "$1" ] && [[ "$1" == *.jar ]] && [ -f "$1" ]; then
  JAR="$1"
  shift
else
  JAR=$(ls target/main-*.jar 2>/dev/null | grep -v 'original-' | head -n 1 || true)
fi

if [ -z "$JAR" ]; then
  echo "错误: 未找到可执行 jar。请先执行 mvn clean package -DskipTests，或直接指定 jar 路径。"
  exit 1
fi
echo "启动 jar: $JAR"

# 2. JVM 参数（默认 128m 起步 / 512m 上限；低内存设备可设 XIUXIAN_MEM=-Xmx256m）
if [ -z "${JAVA_OPTS:-}" ]; then
  JAVA_OPTS="-Xms128m ${XIUXIAN_MEM:--Xmx512m} -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
fi

# 3. 运行（建议从本脚本所在目录启动，保证 db/data/log/adapters.json 等相对路径落在正确位置）
exec java $JAVA_OPTS -jar "$JAR" "$@"
