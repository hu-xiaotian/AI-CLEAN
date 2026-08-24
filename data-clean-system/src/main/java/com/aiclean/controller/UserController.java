package com.aiclean.controller;

import com.aiclean.annotation.RequirePermission;
import com.aiclean.common.R;
import com.aiclean.common.UserContext;
import com.aiclean.controller.GlobalExceptionHandler.BusinessException;
import com.aiclean.entity.SysOperationLog;
import com.aiclean.entity.SysUser;
import com.aiclean.service.OperationLogService;
import com.aiclean.service.UserService;
import com.aiclean.vo.UserFormVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 用户管理控制器
 * 仅管理员可访问（除分页查询外，写操作均校验角色）
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "用户管理模块", description = "用户列表、新增、编辑、删除、启停、重置密码")
@Slf4j
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final OperationLogService operationLogService;

    /**
     * 校验当前用户是否为管理员
     */
    private void assertAdmin() {
        if (!"admin".equals(UserContext.getRole())) {
            throw new BusinessException("无权限，仅管理员可操作用户");
        }
    }

    /**
     * 记录用户管理操作日志
     */
    private void recordUserLog(String action, String desc, boolean success, String errorMsg, Long duration) {
        try {
            operationLogService.record(new SysOperationLog() {{
                setAction(action);
                setModule("用户管理");
                setActionDesc(desc);
                setStatus(success ? 1 : 0);
                if (errorMsg != null) {
                    setErrorMsg(errorMsg);
                }
                if (duration != null) {
                    setDuration(duration);
                }
            }});
        } catch (Exception e) {
            log.error("记录用户操作日志失败", e);
        }
    }

    /**
     * 分页查询用户
     */
    @GetMapping
    @Operation(summary = "分页查询用户", description = "支持按用户名/姓名关键字搜索")
    @RequirePermission("page:users")
    public R<IPage<java.util.Map<String, Object>>> list(@RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "10") long size,
                                                       @RequestParam(required = false) String keyword) {
        return R.success(userService.pageUsersWithRoles(page, size, keyword));
    }

    /**
     * 查询指定用户已分配的角色编码
     */
    @GetMapping("/{id}/roles")
    @Operation(summary = "查询用户已分配角色", description = "返回该用户拥有的角色编码列表")
    @RequirePermission("page:users")
    public R<java.util.List<String>> rolesOfUser(@PathVariable Long id) {
        return R.success(userService.listRoleCodes(id));
    }

    /**
     * 新增用户
     */
    @PostMapping
    @Operation(summary = "新增用户")
    public R<Void> create(@Valid @RequestBody UserFormVO vo) {
        long start = System.currentTimeMillis();
        assertAdmin();
        try {
            userService.createUser(vo);
            recordUserLog("user:create", "新增用户：" + vo.getUsername(), true, null, System.currentTimeMillis() - start);
            return R.success("创建成功");
        } catch (Exception e) {
            recordUserLog("user:create", "新增用户：" + vo.getUsername(), false, e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    /**
     * 编辑用户
     */
    @PutMapping("/{id}")
    @Operation(summary = "编辑用户")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UserFormVO vo) {
        long start = System.currentTimeMillis();
        assertAdmin();
        try {
            userService.updateUser(id, vo);
            recordUserLog("user:update", "编辑用户 ID=" + id + "（" + vo.getUsername() + "）", true, null, System.currentTimeMillis() - start);
            return R.success("更新成功");
        } catch (Exception e) {
            recordUserLog("user:update", "编辑用户 ID=" + id, false, e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除用户")
    public R<Void> delete(@PathVariable Long id) {
        long start = System.currentTimeMillis();
        assertAdmin();
        try {
            userService.deleteUser(id);
            recordUserLog("user:delete", "删除用户 ID=" + id, true, null, System.currentTimeMillis() - start);
            return R.success("删除成功");
        } catch (Exception e) {
            recordUserLog("user:delete", "删除用户 ID=" + id, false, e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    /**
     * 启用/禁用用户
     */
    @PostMapping("/{id}/status")
    @Operation(summary = "启用/禁用用户")
    public R<Void> status(@PathVariable Long id, @RequestParam Integer status) {
        long start = System.currentTimeMillis();
        assertAdmin();
        try {
            userService.updateStatus(id, status);
            recordUserLog("user:status", (status != null && status == 1 ? "启用" : "禁用") + "用户 ID=" + id, true, null, System.currentTimeMillis() - start);
            return R.success(status == 1 ? "已启用" : "已禁用");
        } catch (Exception e) {
            recordUserLog("user:status", "变更用户状态 ID=" + id, false, e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    /**
     * 重置密码为默认密码
     */
    @PostMapping("/{id}/reset-password")
    @Operation(summary = "重置密码")
    public R<Void> resetPassword(@PathVariable Long id) {
        long start = System.currentTimeMillis();
        assertAdmin();
        try {
            userService.resetPassword(id);
            recordUserLog("user:resetpwd", "重置用户密码 ID=" + id, true, null, System.currentTimeMillis() - start);
            return R.success("密码已重置为 admin123");
        } catch (Exception e) {
            recordUserLog("user:resetpwd", "重置用户密码 ID=" + id, false, e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }
}
