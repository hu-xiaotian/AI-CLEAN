package com.aiclean.controller;

import com.aiclean.common.R;
import com.aiclean.common.UserContext;
import com.aiclean.controller.GlobalExceptionHandler.BusinessException;
import com.aiclean.entity.SysUser;
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

    /**
     * 校验当前用户是否为管理员
     */
    private void assertAdmin() {
        if (!"admin".equals(UserContext.getRole())) {
            throw new BusinessException("无权限，仅管理员可操作用户");
        }
    }

    /**
     * 分页查询用户
     */
    @GetMapping
    @Operation(summary = "分页查询用户", description = "支持按用户名/姓名关键字搜索")
    public R<IPage<SysUser>> list(@RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "10") long size,
                                  @RequestParam(required = false) String keyword) {
        return R.success(userService.pageUsers(page, size, keyword));
    }

    /**
     * 新增用户
     */
    @PostMapping
    @Operation(summary = "新增用户")
    public R<Void> create(@Valid @RequestBody UserFormVO vo) {
        assertAdmin();
        userService.createUser(vo);
        return R.success("创建成功");
    }

    /**
     * 编辑用户
     */
    @PutMapping("/{id}")
    @Operation(summary = "编辑用户")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UserFormVO vo) {
        assertAdmin();
        userService.updateUser(id, vo);
        return R.success("更新成功");
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除用户")
    public R<Void> delete(@PathVariable Long id) {
        assertAdmin();
        userService.deleteUser(id);
        return R.success("删除成功");
    }

    /**
     * 启用/禁用用户
     */
    @PostMapping("/{id}/status")
    @Operation(summary = "启用/禁用用户")
    public R<Void> status(@PathVariable Long id, @RequestParam Integer status) {
        assertAdmin();
        userService.updateStatus(id, status);
        return R.success(status == 1 ? "已启用" : "已禁用");
    }

    /**
     * 重置密码为默认密码
     */
    @PostMapping("/{id}/reset-password")
    @Operation(summary = "重置密码")
    public R<Void> resetPassword(@PathVariable Long id) {
        assertAdmin();
        userService.resetPassword(id);
        return R.success("密码已重置为 admin123");
    }
}
