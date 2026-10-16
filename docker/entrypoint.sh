#!/bin/sh
# 容器入口：把镜像内的 jar 同步到 /app，再以 /app 为工作目录启动。
#
# 为什么是「复制到 /app 再跑」而不是直接 java -jar /opt/xiuxian/main.jar：
#   服务端对路径有两套解析基准 ——
#     • config/application.yml、config/plugins.yml        按「jar 所在目录」解析
#     • config/adapters.json、xiuxian.db、data/、log/、plugins/  按「工作目录」解析
#   Main.reportDeploymentPaths() 会在两者不一致时打印告警。
#   把 jar 放在 /app 并 cd /app，两套基准就统一指向 /app，
#   正好落在下面挂载的命名卷里，状态不会丢。
#
# 每次启动都覆盖复制，是为了避免命名卷里残留上一个镜像版本的旧 jar。
set -e

SRC_JAR=/opt/xiuxian/main.jar
DST_JAR=/app/main.jar

if [ ! -f "$SRC_JAR" ]; then
  echo "[entrypoint] 镜像内未找到 $SRC_JAR，构建可能失败" >&2
  exit 1
fi

echo "[entrypoint] 同步 jar: $SRC_JAR -> $DST_JAR"
cp -f "$SRC_JAR" "$DST_JAR"

# 首次运行时补齐状态目录；这些目录位于命名卷中，可持久化
mkdir -p /app/config /app/data /app/log /app/plugins

cd /app

# JAVA_OPTS 有意不加引号：需要按空格拆成多个 JVM 参数
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar "$DST_JAR" "$@"
