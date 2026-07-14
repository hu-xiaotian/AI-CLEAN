package com.aiclean.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis Plus 配置
 * 注册分页插件，使 selectPage 等分页查询生效
 */
@Configuration
public class MybatisPlusConfig {

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor paginationInterceptor;
        if (datasourceUrl != null && datasourceUrl.toLowerCase().contains("jdbc:dm:")) {
            // 达梦数据库开启 compatibleMode=oracle 后，内置 OracleDialect 生成的分页 SQL 外层派生表缺少别名，
            // 会导致 "Every derived table must have its own alias"，故使用自定义方言 DmPaginationDialect 补上别名
            paginationInterceptor = new PaginationInnerInterceptor(new DmPaginationDialect());
        } else {
            // 其他数据库（MySQL 等）使用默认方言（按连接自动识别），生成正确的 LIMIT/OFFSET 分页 SQL
            paginationInterceptor = new PaginationInnerInterceptor();
        }
        interceptor.addInnerInterceptor(paginationInterceptor);
        return interceptor;
    }
}
