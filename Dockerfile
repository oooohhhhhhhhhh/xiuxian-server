# syntax=docker/dockerfile:1
#
# 修仙文字游戏服务器 —— 容器镜像
#
# 构建：
#   docker build -t xiuxian-server .
# 运行：
#   docker compose up -d          （推荐，见 docker-compose.yml）
#
# 说明：本文件未在本机做过 docker build 验证（开发机没有 Docker），
# 但所有路径解析都对齐了 Main.reportDeploymentPaths() 的规则，见下方注释。

# ============================================================
# 构建阶段：用项目自带的 Maven wrapper 编译，无需预装 Maven
# ============================================================
FROM eclipse-temurin:23-jdk AS build

WORKDIR /build

# 先只复制构建描述文件，让「下载依赖」单独成为一层。
# 只要 pom.xml 不变，后续改 Java 代码不会重新下载依赖。
COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q -DskipTests dependency:go-offline

# 再复制源码并打包（跳过测试：CI 里已经跑过全套）
COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

# ============================================================
# 运行阶段
# ============================================================
FROM eclipse-temurin:23-jre

LABEL org.opencontainers.image.title="xiuxian-server" \
      org.opencontainers.image.description="修仙文字游戏服务器（REST + WebSocket + 管理后台）" \
      org.opencontainers.image.source="https://github.com/oooohhhhhhhhhh/xiuxian-server-alpha"

# JAVA_OPTS 可用环境变量覆盖；默认值面向小内存机器
# （本项目历史上多次出现 hs_err「out of physical RAM or swap space」）
ENV JAVA_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m" \
    TZ=Asia/Shanghai \
    XIUXIAN_PORT=8080

# curl 仅用于健康检查
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# jar 放在镜像内的固定位置，作为「只读母本」。
# 入口脚本会在每次启动时把它同步到 /app，原因见 docker/entrypoint.sh：
# 服务端的 config/application.yml、config/plugins.yml 按「jar 所在目录」解析，
# 而 config/adapters.json、xiuxian.db、data/、log/、plugins/ 按「工作目录」解析。
# 让 jar 目录 == 工作目录 == /app，两套基准才一致。
COPY --from=build /build/target/main-*.jar /opt/xiuxian/main.jar
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# /app 既是工作目录也是 jar 目录，同时承载全部可持久化状态
RUN mkdir -p /app/config /app/data /app/log /app/plugins \
 && useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin xiuxian \
 && chown -R xiuxian:xiuxian /app /opt/xiuxian

WORKDIR /app

# 声明为卷：config/ data/ log/ plugins/ 与 xiuxian.db 都在 /app 下
VOLUME ["/app"]

EXPOSE 8080

# 复用服务端自己的就绪探针：GET /api/game/status
# 该端点未登录时返回 401，属正常「已就绪」；curl 不加 -f 时 401 仍退出 0，
# 连接被拒才退出非 0，正好是我们要的语义。
# 注意：若改了 server.port，需要同步改 XIUXIAN_PORT。
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -s -o /dev/null --max-time 3 "http://127.0.0.1:${XIUXIAN_PORT}/api/game/status" || exit 1

USER xiuxian

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
