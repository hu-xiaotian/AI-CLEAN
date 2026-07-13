@echo off
chcp 65001 >nul
echo ==========================================
echo AI Clean 数据清洗系统启动脚本
echo 版本: 1.0.0
echo ==========================================

REM 检查Java版本
echo 检查Java版本...
java -version 2>&1 | findstr /i "version" >nul
if %errorlevel% equ 0 (
    for /f "tokens=3" %%i in ('java -version 2^>^&1 ^| findstr /i "version"') do (
        set java_version=%%i
    )
    echo ✓ Java版本: %java_version%
) else (
    echo ✗ Java未安装或未配置环境变量
    echo 请安装Java 17或更高版本
    pause
    exit /b 1
)

REM 检查Maven
echo 检查Maven...
mvn -version >nul 2>&1
if %errorlevel% equ 0 (
    for /f "tokens=3" %%i in ('mvn -version ^| findstr "Apache Maven"') do (
        set mvn_version=%%i
    )
    echo ✓ Maven版本: %mvn_version%
) else (
    echo ✗ Maven未安装或未配置环境变量
    echo 请安装Maven 3.6+
    pause
    exit /b 1
)

REM 检查配置文件
echo 检查数据库配置...
if exist "src\main\resources\application.yml" (
    echo ✓ 找到配置文件: application.yml
    
    REM 简单检查数据库类型
    findstr /i "driver-class-name" src\main\resources\application.yml | findstr /i "mysql" >nul
    if %errorlevel% equ 0 (
        echo ✓ 数据库类型: MySQL
        echo 请确保MySQL服务已启动，并执行以下命令初始化数据库:
        echo   mysql -u root -p ^< sql\setup-simple.sql
    ) else (
        findstr /i "driver-class-name" src\main\resources\application.yml | findstr /i "dm" >nul
        if %errorlevel% equ 0 (
            echo ✓ 数据库类型: 达梦数据库
            echo 请确保达梦数据库服务已启动
        ) else (
            echo ⚠ 未知数据库类型，请检查application.yml配置
        )
    )
) else (
    echo ✗ 配置文件application.yml不存在
    pause
    exit /b 1
)

REM 创建必要目录
echo 创建必要目录...
if not exist "uploads" mkdir uploads
if not exist "exports" mkdir exports
if not exist "logs" mkdir logs
echo ✓ 目录创建完成

echo.
echo 请选择启动模式:
echo 1. 构建并启动 (首次启动推荐)
echo 2. 仅启动 (已构建)
echo 3. 仅构建
echo 4. 运行测试
echo 5. 清理并重新构建
set /p choice="请输入选项 [1-5]: "

if "%choice%"=="1" (
    echo 执行: 清理、构建并启动...
    call mvn clean package
    if %errorlevel% equ 0 (
        echo ✓ 构建成功
        echo 启动应用...
        java -jar target\data-clean-system-1.0.0.jar
    ) else (
        echo ✗ 构建失败
        pause
        exit /b 1
    )
) else if "%choice%"=="2" (
    echo 执行: 启动应用...
    if exist "target\data-clean-system-1.0.0.jar" (
        java -jar target\data-clean-system-1.0.0.jar
    ) else (
        echo ✗ 未找到可执行jar文件，请先构建
        pause
        exit /b 1
    )
) else if "%choice%"=="3" (
    echo 执行: 构建...
    call mvn clean package
) else if "%choice%"=="4" (
    echo 执行: 运行测试...
    call mvn test
) else if "%choice%"=="5" (
    echo 执行: 清理并重新构建...
    call mvn clean
    call mvn package
) else (
    echo 无效选项，退出
    pause
    exit /b 1
)

REM 启动成功提示
if "%choice%"=="1" (
    echo.
    echo ==========================================
    echo 应用启动成功！
    echo.
    echo 访问地址:
    echo   - 应用主页: http://localhost:8080
    echo   - API文档: http://localhost:8080/swagger-ui.html
    echo   - 健康检查: http://localhost:8080/api/system/health
    echo.
    echo 主要API接口:
    echo   - 数据导入: http://localhost:8080/api/import/upload
    echo   - 分类管理: http://localhost:8080/api/categories/tree
    echo   - 数据查询: http://localhost:8080/api/cleaned-data
    echo   - 审核任务: http://localhost:8080/api/review/tasks
    echo   - 数据导出: http://localhost:8080/api/export/by-categories
    echo.
    echo 按 Ctrl+C 停止应用
    echo ==========================================
)

if "%choice%"=="2" (
    echo.
    echo ==========================================
    echo 应用启动成功！
    echo 访问地址: http://localhost:8080
    echo 按 Ctrl+C 停止应用
    echo ==========================================
)

pause