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

java -Xms128m -Xmx256m -XX:+UseSerialGC -XX:MaxDirectMemorySize=32m ^
     -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 ^
     -jar target/main-V1.4.1-alpha1.jar %*

pause
