@echo off
:: ======================================
::  修仙世界 - 低内存模式启动脚本
:: ======================================
::  适用: 1GB-2GB 内存设备
::  JVM 参数说明:
::   -Xms128m  初始堆 128MB
::   -Xmx256m  最大堆 256MB (不够可改为 512m)
::   -XX:+UseSerialGC  串行 GC，单线程回收，内存开销最低
::   -XX:MaxDirectMemorySize=32m  限制堆外内存
:: ======================================
setlocal

cd /d "%~dp0"

:: 自动定位 jar（按修改时间取最新，排除 original-* 未 shade 的原始包）。
:: 刻意不写死带版本号的文件名：版本号一改，写死的路径就会失效。
set "JAR="
for /f "delims=" %%f in ('dir /b /o-d "target\main-*.jar" 2^>nul ^| findstr /v /i "original-"') do (
    if not defined JAR set "JAR=target\%%f"
)

if not defined JAR (
    echo 错误: 未找到可执行 jar。请先执行 mvnw.cmd clean package -DskipTests
    pause
    exit /b 1
)

echo 启动 jar: %JAR%
java -Xms128m -Xmx256m -XX:+UseSerialGC -XX:MaxDirectMemorySize=32m ^
     -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 ^
     -jar "%JAR%" %*

pause
