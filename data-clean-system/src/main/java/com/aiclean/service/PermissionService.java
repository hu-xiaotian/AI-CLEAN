package com.aiclean.service;

import com.aiclean.entity.SysPermission;

import java.util.List;
import java.util.Map;

/**
 * 权限配置服务
 */
public interface PermissionService {

    /**
     * 查询所有权限，按模块分组返回（用于权限配置页面展示）
     *
     * @return key=模块名，value=该模块下权限列表
     */
    Map<String, List<SysPermission>> listPermissionsGroupedByModule();

    /**
     * 查询指定角色已分配的权限ID列表
     *
     * @param roleCode 角色编码
     * @return 已分配权限ID集合
     */
    List<Long> listPermissionIdsByRole(String roleCode);

    /**
     * 为角色重新分配权限（全量覆盖）
     *
     * @param roleCode  角色编码
     * @param permIds   权限ID列表
     */
    void assignPermissions(String roleCode, List<Long> permIds);

    /**
     * 查询指定用户通过其所有角色继承到的权限编码集合
     *
     * @param userId 用户ID
     * @return 权限编码列表（已去重）
     */
    List<String> listPermissionCodesByUserId(Long userId);
}
