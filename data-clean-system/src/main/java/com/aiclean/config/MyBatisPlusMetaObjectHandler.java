package com.aiclean.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis Plus 自动填充处理器
 */
@Component
public class MyBatisPlusMetaObjectHandler implements MetaObjectHandler {
    
    @Override
    public void insertFill(MetaObject metaObject) {
        // 设置创建时间
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        
        // 设置更新时间
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        
        // 设置创建人（从当前用户获取）
        String currentUser = getCurrentUser();
        if (currentUser != null) {
            this.strictInsertFill(metaObject, "createdBy", String.class, currentUser);
            this.strictInsertFill(metaObject, "updatedBy", String.class, currentUser);
        }
    }
    
    @Override
    public void updateFill(MetaObject metaObject) {
        // 设置更新时间
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        
        // 设置更新人（从当前用户获取）
        String currentUser = getCurrentUser();
        if (currentUser != null) {
            this.strictUpdateFill(metaObject, "updatedBy", String.class, currentUser);
        }
    }
    
    /**
     * 获取当前用户（实际项目中从安全上下文中获取）
     */
    private String getCurrentUser() {
        // TODO: 从Spring Security上下文中获取当前用户
        // 临时返回固定值
        return "system";
    }
}