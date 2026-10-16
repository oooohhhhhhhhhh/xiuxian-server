@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ================================================================================
echo 修仙服务器测试套件
echo ================================================================================
echo.

rem ---- 选择 Maven：优先项目自带 wrapper，否则用全局 mvn ----
set "MVN="
if exist "mvnw.cmd" (
    set "MVN=mvnw.cmd"
    echo [i] 使用项目自带 Maven wrapper: mvnw.cmd
) else (
    where mvn >nul 2>nul
    if errorlevel 1 (
        echo ✗ 既找不到 mvnw.cmd，也找不到全局 mvn
        echo   请安装 Maven 3.8+，或确认项目根目录存在 mvnw / mvnw.cmd
        pause
        exit /b 1
    )
    set "MVN=mvn"
    echo [i] 使用全局 Maven: mvn
)
echo.

rem ---- 低内存机器上的堆设置；外部已设置时不覆盖 ----
if not defined MAVEN_OPTS set "MAVEN_OPTS=-Xmx384m -XX:MaxMetaspaceSize=256m -Xss1m -XX:+UseSerialGC"

echo [1/2] 清理旧的测试数据库...
if exist test_combat.db del /q test_combat.db
if exist test_combat.db-shm del /q test_combat.db-shm
if exist test_combat.db-wal del /q test_combat.db-wal
if exist test_full.db del /q test_full.db
if exist test_full.db-shm del /q test_full.db-shm
if exist test_full.db-wal del /q test_full.db-wal
if exist xiuxian_test.db del /q xiuxian_test.db
if exist xiuxian_test.db-shm del /q xiuxian_test.db-shm
if exist xiuxian_test.db-wal del /q xiuxian_test.db-wal
echo ✓ 清理完成
echo.

echo [2/2] 编译并运行全部测试...
echo ----------------------------------------
call %MVN% clean test %*
set "STATUS=%errorlevel%"
echo ----------------------------------------
echo.

if not "%STATUS%"=="0" (
    echo ✗ 测试失败（退出码 %STATUS%）
    echo   详细报告: target\surefire-reports\
    pause
    exit /b %STATUS%
)

echo ================================================================================
echo ✓ 测试全部通过
echo ================================================================================
pause
