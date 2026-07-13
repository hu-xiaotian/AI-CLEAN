package com.aiclean.config;

import com.aiclean.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * 应用启动数据初始化器
 * 系统首次启动、无任何用户时，自动创建默认管理员账号
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final AuthService authService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            authService.initDefaultAdmin();
        } catch (Exception e) {
            log.warn("初始化默认管理员账号失败（可能 sys_user 表尚未创建）: {}", e.getMessage());
        }
    }
}
