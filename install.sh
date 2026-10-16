#!/usr/bin/env bash
# ============================================================
#  修仙世界 · 一键安装启动脚本（Linux / macOS）
#  用法:
#    ./install.sh            首次安装并启动（浏览器打开 /install 完成配置）
#    ./install.sh --nogui    无交互模式启动（按 Ctrl+C 停止）
#    ./install.sh --build    强制重新构建后再启动
#    ./install.sh --help     查看帮助
# ============================================================
set -e

cd "$(dirname "$0")"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[安装]${NC} $1"; }
warn()  { echo -e "${YELLOW}[提示]${NC} $1"; }
err()   { echo -e "${RED}[错误]${NC} $1"; exit 1; }

BUILD=0
ARGS=()
for a in "$@"; do
  case "$a" in
    --build) BUILD=1 ;;
    --help|-h)
      sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) ARGS+=("$a") ;;
  esac
done

# ---------- 1. 检查 Java ----------
if ! command -v java >/dev/null 2>&1; then
  err "未检测到 Java，请先安装 JDK 23+（https://adoptium.net/）"
fi
JAVA_VERSION=$(java -version 2>&1 | head -n1 | sed -E 's/.*version "([0-9]+).*/\1/')
if [ -z "$JAVA_VERSION" ]; then
  # 老版本格式 1.8.0_xxx
  JAVA_VERSION=$(java -version 2>&1 | head -n1 | sed -E 's/.*version "1\.([0-9]+).*/\1/')
fi
if [ "$JAVA_VERSION" -lt 23 ] 2>/dev/null; then
  err "Java 版本过低（当前 $JAVA_VERSION），需要 JDK 23 或更高"
fi
info "Java 环境 OK：$(java -version 2>&1 | head -n1 | sed 's/^java version //;s/^openjdk version //')"

# ---------- 2. 定位/构建 jar ----------
JAR=$(ls target/main-*.jar 2>/dev/null | head -n1)
if [ -z "$JAR" ]; then
  JAR=$(ls main-*.jar 2>/dev/null | head -n1)
fi
if [ -z "$JAR" ] || [ "$BUILD" = "1" ]; then
  # 优先用项目自带的 Maven wrapper，这样无需本机预装 Maven
  if [ -x "./mvnw" ]; then
    MVN="./mvnw"
  elif command -v mvn >/dev/null 2>&1; then
    MVN="mvn"
  else
    err "未找到可执行 jar，且既无 ./mvnw 也未检测到 Maven。请提供 main-*.jar 或安装 Maven 3.8+"
  fi
  info "正在构建服务器（首次构建约需 1-3 分钟）…"
  MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" $MVN -q package -DskipTests
  JAR=$(ls target/main-*.jar 2>/dev/null | head -n1)
  [ -z "$JAR" ] && err "构建完成但未找到 jar，请检查构建输出"
fi
info "使用 jar：$JAR"

# ---------- 3. 配置检查 ----------
if [ ! -f config/application.yml ]; then
  warn "尚未生成配置文件，首次启动将自动释放默认配置并打开安装向导"
fi
if [ -f config/application.yml ] && ! grep -q '^  secret: .\+' config/application.yml; then
  warn "检测到未配置 JWT 密钥：首次启动将打开安装向导（/install）完成配置"
fi

# ---------- 4. 启动 ----------
PORT=$(grep -E '^  port:' config/application.yml 2>/dev/null | head -n1 | awk '{print $2}')
[ -z "$PORT" ] && PORT=8080
info "服务器即将启动（端口 ${PORT}）"
info "首次安装：请在浏览器打开  http://127.0.0.1:${PORT}/install"
info "管理后台：http://127.0.0.1:${PORT}/admin/   （安装完成后可用）"
echo "------------------------------------------------------"

exec java -jar "$JAR" "${ARGS[@]}"
