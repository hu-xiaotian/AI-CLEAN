package com.aiclean.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户上下文（基于 ThreadLocal）
 */
public class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static Long getUserId() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.getUserId();
    }

    public static String getUsername() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.getUsername();
    }

    public static String getRole() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.getRole();
    }

    public static String getRealName() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.getRealName();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 当前用户信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrentUser {
        private Long userId;
        private String username;
        private String role;
        private String realName;

        /**
         * 兼容旧的 3 参调用（userId, username, role）
         */
        public CurrentUser(Long userId, String username, String role) {
            this(userId, username, role, null);
        }
    }
}
