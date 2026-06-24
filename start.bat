@echo off
chcp 65001 >nul
echo ========================================
echo iLink 项目启动脚本
echo ========================================
echo.

REM 设置JDK路径
set JAVA_HOME=E:\huan_jing\jdk-17.0.13_windows-x64_bin\jdk-17.0.13
set PATH=%JAVA_HOME%\bin;%PATH%

REM 检查Java版本
echo 检查Java环境...
java -version
echo.

REM 启动Spring Boot项目
echo 正在启动iLink项目...
echo 启动后请访问: http://localhost:8090
echo 按 Ctrl+C 停止服务
echo.

mvn spring-boot:run

pause
