package com.aiclean.service;

import com.aiclean.entity.SysUser;
import com.aiclean.vo.UserFormVO;
import com.baomidou.mybatisplus.core.metadata.IPage;

/**
 * 用户管理服务
 */
public interface UserService {

    /**
     * 分页查询用户
     */
    IPage<SysUser> pageUsers(long page, long size, String keyword);

    /**
     * 新增用户
     */
    void createUser(UserFormVO vo);

    /**
     * 编辑用户
     */
    void updateUser(Long id, UserFormVO vo);

    /**
     * 删除用户
     */
    void deleteUser(Long id);

    /**
     * 启用/禁用用户
     */
    void updateStatus(Long id, Integer status);

    /**
     * 重置密码为默认密码
     */
    void resetPassword(Long id);
}
