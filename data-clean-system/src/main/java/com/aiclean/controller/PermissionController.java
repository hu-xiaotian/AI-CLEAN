package com.aiclean.controller;

import com.aiclean.common.R;
import com.aiclean.common.UserContext;
import com.aiclean.entity.SysPermission;
import com.aiclean.service.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 权限配置控制器
 */
@RestController
@RequestMapping("/api/permissions")
@Tag(name = "权限配置模块", description = "权限查询与角色权限分配接口")
@Slf4j
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    /**
     * 获取全部权限（按模块分组），用于权限配置页面展示
     */
    @GetMapping("/all")
    @Operation(summary = "获取全部权限（按模块分组）", description = "返回按功能模块分组的权限列表，供权限配置页查看")
    public R<Map<String, List<SysPermission>>> listAll() {
        return R.success(permissionService.listPermissionsGroupedByModule());
    }

    /**
     * 获取指定角色已分配的权限ID
     */
    @GetMapping("/role")
    @Operation(summary = "获取角色已分配权限", description = "根据角色编码返回其已拥有的权限ID列表")
    public R<List<Long>> listByRole(@RequestParam String roleCode) {
        return R.success(permissionService.listPermissionIdsByRole(roleCode));
    }

    /**
     * 为角色分配权限（全量覆盖）
     */
    @PostMapping("/role/assign")
    @Operation(summary = "分配角色权限", description = "为指定角色重新分配权限，permIds 为全量权限ID列表")
    public R<Void> assign(@RequestParam String roleCode, @RequestBody List<Long> permIds) {
        permissionService.assignPermissions(roleCode, permIds);
        return R.success("权限已保存");
    }

    /**
     * 当前登录用户可管理的角色列表（用于权限配置页面的角色切换）
     */
    @GetMapping("/roles")
    @Operation(summary = "获取角色列表", description = "返回系统中可配置权限的角色列表")
    public R<List<Map<String, String>>> listRoles() {
        List<Map<String, String>> roles = java.util.Arrays.asList(
                roleItem("admin", "管理员"),
                roleItem("user", "普通用户")
        );
        return R.success(roles);
    }

    private Map<String, String> roleItem(String code, String name) {
        Map<String, String> m = new java.util.HashMap<>();
        m.put("roleCode", code);
        m.put("roleName", name);
        return m;
    }
}
