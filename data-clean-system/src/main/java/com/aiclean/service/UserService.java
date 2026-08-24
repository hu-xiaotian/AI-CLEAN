package com.aiclean.service;

import com.aiclean.entity.SysUser;
import com.aiclean.vo.UserFormVO;
import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.Map;

/**
 * 用户管理服务
 */
public interface UserService {

    /**
     * 分页查询用户
     */
    IPage<SysUser> pageUsers(long page, long size, String keyword);

    /**
     * 分页查询用户（附带每个用户已分配的角色列表）
     *
     * @param page    页码
     * @param size    每页条数
     * @param keyword 用户名/姓名关键字
     * @return 记录中除用户字段外，额外包含 roleCodes、roleNames
     */
    IPage<Map<String, Object>> pageUsersWithRoles(long page, long size, String keyword);

    /**
     * 查询指定用户已分配的角色编码
     */
    List<String> listRoleCodes(Long userId);

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
