package com.aiclean;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 数据清洗系统主程序
 */
@SpringBootApplication
@EnableTransactionManagement
@EnableCaching
@EnableAsync
@MapperScan("com.aiclean.mapper")
public class DataCleanSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataCleanSystemApplication.class, args);
        System.out.println("========================================");
        System.out.println("数据清洗系统启动成功!");
        System.out.println("访问地址: http://localhost:8080/api");
        System.out.println("========================================");
    }
}