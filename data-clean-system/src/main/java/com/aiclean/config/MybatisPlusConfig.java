package com.aiclean.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis Plus 配置
 * 注册分页插件，使 selectPage 等分页查询生效
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 达梦数据库（其 LIMIT 语法与 MySQL 兼容，开发环境 MySQL 同样适用）
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.DM));
        return interceptor;
    }
}
