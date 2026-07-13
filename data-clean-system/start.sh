#!/bin/bash

# AI Clean 数据清洗系统启动脚本

echo "=========================================="
echo "AI Clean 数据清洗系统启动脚本"
echo "版本: 1.0.0"
echo "=========================================="

# 检查Java版本
echo "检查Java版本..."
java_version=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
if [[ $java_version == 17* ]]; then
    echo "✓ Java版本: $java_version (符合要求)"
else
    echo "✗ Java版本: $java_version (需要Java 17+)"
    echo "请安装Java 17或更高版本"
    exit 1
fi

# 检查Maven
echo "检查Maven..."
if command -v mvn &> /dev/null; then
    mvn_version=$(mvn -version | head -1 | cut -d' ' -f3)
    echo "✓ Maven版本: $mvn_version"
else
    echo "✗ Maven未安装"
    echo "请安装Maven 3.6+"
    exit 1
fi

# 检查数据库连接
echo "检查数据库配置..."
if [ -f "src/main/resources/application.yml" ]; then
    echo "✓ 找到配置文件: application.yml"
    
    # 提取数据库配置
    db_type=$(grep "driver-class-name" src/main/resources/application.yml | head -1)
    if [[ $db_type == *"mysql"* ]]; then
        echo "✓ 数据库类型: MySQL"
        echo "请确保MySQL服务已启动，并执行以下命令初始化数据库:"
        echo "  mysql -u root -p < sql/setup-simple.sql"
    elif [[ $db_type == *"dm"* ]]; then
        echo "✓ 数据库类型: 达梦数据库"
        echo "请确保达梦数据库服务已启动"
    else
        echo "⚠ 未知数据库类型，请检查application.yml配置"
    fi
else
    echo "✗ 配置文件application.yml不存在"
    exit 1
fi

# 创建必要目录
echo "创建必要目录..."
mkdir -p uploads exports logs
echo "✓ 目录创建完成"

# 选择启动模式
echo ""
echo "请选择启动模式:"
echo "1. 构建并启动 (首次启动推荐)"
echo "2. 仅启动 (已构建)"
echo "3. 仅构建"
echo "4. 运行测试"
echo "5. 清理并重新构建"
read -p "请输入选项 [1-5]: " choice

case $choice in
    1)
        echo "执行: 清理、构建并启动..."
        mvn clean package
        if [ $? -eq 0 ]; then
            echo "✓ 构建成功"
            echo "启动应用..."
            java -jar target/data-clean-system-1.0.0.jar
        else
            echo "✗ 构建失败"
            exit 1
        fi
        ;;
    2)
        echo "执行: 启动应用..."
        if [ -f "target/data-clean-system-1.0.0.jar" ]; then
            java -jar target/data-clean-system-1.0.0.jar
        else
            echo "✗ 未找到可执行jar文件，请先构建"
            exit 1
        fi
        ;;
    3)
        echo "执行: 构建..."
        mvn clean package
        ;;
    4)
        echo "执行: 运行测试..."
        mvn test
        ;;
    5)
        echo "执行: 清理并重新构建..."
        mvn clean
        mvn package
        ;;
    *)
        echo "无效选项，退出"
        exit 1
        ;;
esac

# 启动成功提示
if [ $choice -eq 1 ] || [ $choice -eq 2 ]; then
    echo ""
    echo "=========================================="
    echo "应用启动成功！"
    echo ""
    echo "访问地址:"
    echo "  - 应用主页: http://localhost:8080"
    echo "  - API文档: http://localhost:8080/swagger-ui.html"
    echo "  - 健康检查: http://localhost:8080/api/system/health"
    echo ""
    echo "主要API接口:"
    echo "  - 数据导入: http://localhost:8080/api/import/upload"
    echo "  - 分类管理: http://localhost:8080/api/categories/tree"
    echo "  - 数据查询: http://localhost:8080/api/cleaned-data"
    echo "  - 审核任务: http://localhost:8080/api/review/tasks"
    echo "  - 数据导出: http://localhost:8080/api/export/by-categories"
    echo ""
    echo "按 Ctrl+C 停止应用"
    echo "=========================================="
fi