package com.aiclean.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限校验注解
 * <p>标注在 Controller 方法（或类）上，声明访问该接口所需的功能权限编码，
 * 由 {@link com.aiclean.config.JwtAuthInterceptor} 统一校验。</p>
 * <p>内置管理员角色（admin）自动放行；未登录用户已被认证拦截器拦截。</p>
 * <p>示例：{@code @RequirePermission("page:import")} 表示仅拥有「数据导入」页面权限的用户可访问。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {

    /**
     * 所需权限编码，如 page:import / data:clean:start
     */
    String value();
}
