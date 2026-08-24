package com.aiclean.service;

import com.aiclean.entity.SysRole;
import com.aiclean.vo.RoleFormVO;
import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.Map;

/**
 * 角色服务
 */
public interface RoleService {

    /**
     * 分页查询角色（附带用户数、权限数统计）
     *
     * @param page    页码，从 1 开始
     * @param size    每页条数
     * @param keyword 角色编码/名称模糊搜索关键字，可为空
     */
    IPage<Map<String, Object>> pageRoles(int page, int size, String keyword);

    /**
     * 查询全部启用角色（供下拉框使用）
     */
    List<SysRole> listEnabledRoles();

    /**
     * 根据ID查询角色
     */
    SysRole getById(Long id);

    /**
     * 新建角色
     *
     * @return 新建后的角色
     */
    SysRole createRole(RoleFormVO vo);

    /**
     * 修改角色
     */
    SysRole updateRole(Long id, RoleFormVO vo);

    /**
     * 删除角色（内置角色不可删除；已分配用户的角色不可删除）
     */
    void deleteRole(Long id);

    /**
     * 启用/禁用角色
     *
     * @param status 1=启用，0=禁用
     */
    void updateStatus(Long id, Integer status);

    /**
     * 查询指定用户已分配的角色编码列表
     */
    List<String> listRoleCodesByUserId(Long userId);

    /**
     * 给用户分配角色（全量覆盖）
     *
     * @param userId    用户ID
     * @param roleCodes 角色编码列表，传空表示清空该用户所有角色
     */
    void assignRolesToUser(Long userId, List<String> roleCodes);

    /**
     * 查询指定角色下已分配的用户列表
     */
    List<Map<String, Object>> listUsersByRoleCode(String roleCode);
}
