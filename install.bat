@echo off
chcp 65001 >nul
REM ============================================================
REM  修仙世界 · 一键安装启动脚本（Windows）
REM  用法:
REM    install.bat              首次安装并启动（浏览器打开 /install 完成配置）
REM    install.bat --nogui      无交互模式启动（按 Ctrl+C 停止）
REM    install.bat --build      强制重新构建后再启动
REM    install.bat --help       查看帮助
REM ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "BUILD=0"
set "JAVA_ARGS="
for %%a in (%*) do (
  if /i "%%a"=="--build" (set "BUILD=1") else (
    if /i "%%a"=="--help" (goto :help) else (set "JAVA_ARGS=!JAVA_ARGS! %%a")
  )
)

REM ---------- 1. 检查 Java ----------
where java >nul 2>nul
if errorlevel 1 (
  echo [错误] 未检测到 Java，请先安装 JDK 23+ ^(https://adoptium.net/^)
  pause & exit /b 1
)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JV=%%v"
set "JV=%JV:"=%"
for /f "tokens=1 delims=." %%m in ("%JV%") do set "MAJOR=%%m"
if "%MAJOR%"=="1" (
  for /f "tokens=2 delims=." %%m in ("%JV%") do set "MAJOR=%%m"
)
if %MAJOR% LSS 23 (
  echo [错误] Java 版本过低（当前 %JV%），需要 JDK 23 或更高
  pause & exit /b 1
)
echo [安装] Java 环境 OK：%JV%

REM ---------- 2. 定位/构建 jar ----------
set "JAR="
for /f "delims=" %%f in ('dir /b /o-d target\main-*.jar 2^>nul') do if not defined JAR set "JAR=target\%%f"
if not defined JAR for /f "delims=" %%f in ('dir /b /o-d main-*.jar 2^>nul') do if not defined JAR set "JAR=%%f"
if not defined JAR set "BUILD=1"

REM 优先用项目自带的 Maven wrapper，这样无需本机预装 Maven。
REM 注意：必须在下方 if 块之外解析 MVN，否则块内的 %MVN% 会在解析期展开为空。
set "MVN="
if exist "mvnw.cmd" (
  set "MVN=mvnw.cmd"
) else (
  where mvn >nul 2>nul
  if not errorlevel 1 set "MVN=mvn"
)

if "%BUILD%"=="1" (
  if not defined MVN (
    echo [错误] 未找到可执行 jar，且既无 mvnw.cmd 也未检测到 Maven。请提供 main-*.jar 或安装 Maven 3.8+
    pause & exit /b 1
  )
  echo [安装] 正在构建服务器（首次构建约需 1-3 分钟）…
  set "MAVEN_OPTS=-Xmx1024m -XX:MaxMetaspaceSize=384m"
  call %MVN% -q package -DskipTests
  if errorlevel 1 ( echo [错误] 构建失败 & pause & exit /b 1 )
  set "JAR="
  for /f "delims=" %%f in ('dir /b /o-d target\main-*.jar 2^>nul') do if not defined JAR set "JAR=target\%%f"
  if not defined JAR ( echo [错误] 构建完成但未找到 jar & pause & exit /b 1 )
)
echo [安装] 使用 jar：%JAR%

REM ---------- 3. 配置检查 ----------
if not exist config\application.yml (
  echo [提示] 尚未生成配置文件，首次启动将自动释放默认配置并打开安装向导
)
if exist config\application.yml (
  findstr /r /c:"^  secret: ..*" config\application.yml >nul 2>nul
  if errorlevel 1 (
    echo [提示] 检测到未配置 JWT 密钥：首次启动将打开安装向导 ^(/install^) 完成配置
  )
)

REM ---------- 4. 启动 ----------
set "PORT=8080"
if exist config\application.yml (
  for /f "tokens=2" %%p in ('findstr /r /c:"^  port:" config\application.yml 2^>nul') do set "PORT=%%p"
)
echo [安装] 服务器即将启动（端口 %PORT%）
echo [安装] 首次安装：请在浏览器打开  http://127.0.0.1:%PORT%/install
echo [安装] 管理后台：http://127.0.0.1:%PORT%/admin/   ^（安装完成后可用^）
echo ------------------------------------------------------

java -jar "%JAR%"%JAVA_ARGS%
goto :eof

:help
type "%~f0" | findstr /b "REM" | findstr /v "^REM ===" 
pause
