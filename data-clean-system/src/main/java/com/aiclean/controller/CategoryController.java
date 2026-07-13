package com.aiclean.controller;

import com.aiclean.common.R;
import com.aiclean.entity.CategoryEntity;
import com.aiclean.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类管理控制器
 * 负责处理层级分类的增删改查、树形结构展示、移动排序等操作
 */
@RestController
@RequestMapping("/api/categories")
@Tag(name = "分类管理模块", description = "层级分类的增删改查、树形结构管理接口")
@Slf4j
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    /**
     * 创建分类
     */
    @PostMapping
    @Operation(summary = "创建分类", description = "创建新的分类节点")
    public R<CategoryEntity> createCategory(@RequestBody CategoryEntity category) {
        try {
            CategoryEntity created = categoryService.createCategory(category);
            return R.success("分类创建成功", created);
        } catch (Exception e) {
            log.error("创建分类失败", e);
            return R.error("创建分类失败: " + e.getMessage());
        }
    }

    /**
     * 更新分类信息
     */
    @PutMapping("/{id}")
    @Operation(summary = "更新分类信息", description = "更新指定分类的基本信息")
    public R<CategoryEntity> updateCategory(@PathVariable Long id, @RequestBody CategoryEntity category) {
        try {
            category.setId(id);
            CategoryEntity updated = categoryService.updateCategory(category);
            return R.success("分类更新成功", updated);
        } catch (Exception e) {
            log.error("更新分类失败", e);
            return R.error("更新分类失败: " + e.getMessage());
        }
    }

    /**
     * 删除分类
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除分类", description = "删除指定分类及其所有子分类")
    public R<Boolean> deleteCategory(@PathVariable Long id) {
        try {
            categoryService.deleteCategory(id);
            return R.success("分类删除成功", true);
        } catch (Exception e) {
            log.error("删除分类失败", e);
            return R.error("删除分类失败: " + e.getMessage());
        }
    }

    /**
     * 获取分类详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取分类详情", description = "获取指定分类的详细信息")
    public R<CategoryEntity> getCategoryById(@PathVariable Long id) {
        try {
            CategoryEntity category = categoryService.getCategoryById(id);
            return R.success("分类详情获取成功", category);
        } catch (Exception e) {
            log.error("获取分类详情失败", e);
            return R.error("获取分类详情失败: " + e.getMessage());
        }
    }

    /**
     * 获取分类树
     */
    @GetMapping("/tree")
    @Operation(summary = "获取分类树", description = "获取完整的分类树形结构")
    public R<List<CategoryEntity>> getCategoryTree(
            @RequestParam(value = "parentId", required = false) Long parentId) {
        try {
            List<CategoryEntity> tree = categoryService.getCategoryTree(parentId);
            return R.success("分类树获取成功", tree);
        } catch (Exception e) {
            log.error("获取分类树失败", e);
            return R.error("获取分类树失败: " + e.getMessage());
        }
    }

    /**
     * 获取子分类列表
     */
    @GetMapping("/{parentId}/children")
    @Operation(summary = "获取子分类列表", description = "获取指定父分类下的所有子分类")
    public R<List<CategoryEntity>> getChildCategories(@PathVariable Long parentId) {
        try {
            List<CategoryEntity> children = categoryService.getChildCategories(parentId);
            return R.success("子分类列表获取成功", children);
        } catch (Exception e) {
            log.error("获取子分类列表失败", e);
            return R.error("获取子分类列表失败: " + e.getMessage());
        }
    }

    /**
     * 移动分类
     */
    @PutMapping("/{id}/move")
    @Operation(summary = "移动分类", description = "将分类移动到新的父分类下")
    public R<CategoryEntity> moveCategory(
            @PathVariable Long id,
            @RequestParam(value = "newParentId", required = false) Long newParentId) {
        try {
            CategoryEntity moved = categoryService.moveCategory(id, newParentId);
            return R.success("分类移动成功", moved);
        } catch (Exception e) {
            log.error("移动分类失败", e);
            return R.error("移动分类失败: " + e.getMessage());
        }
    }

    /**
     * 排序分类
     */
    @PutMapping("/{id}/sort")
    @Operation(summary = "排序分类", description = "更新分类在同级中的排序位置")
    public R<CategoryEntity> sortCategory(
            @PathVariable Long id,
            @RequestParam("newSortOrder") Integer newSortOrder) {
        try {
            CategoryEntity sorted = categoryService.sortCategory(id, newSortOrder);
            return R.success("分类排序更新成功", sorted);
        } catch (Exception e) {
            log.error("更新分类排序失败", e);
            return R.error("更新分类排序失败: " + e.getMessage());
        }
    }

    /**
     * 搜索分类
     */
    @GetMapping("/search")
    @Operation(summary = "搜索分类", description = "根据关键词搜索分类")
    public R<List<CategoryEntity>> searchCategories(@RequestParam("keyword") String keyword) {
        try {
            List<CategoryEntity> results = categoryService.searchCategories(keyword);
            return R.success("分类搜索成功", results);
        } catch (Exception e) {
            log.error("搜索分类失败", e);
            return R.error("搜索分类失败: " + e.getMessage());
        }
    }

    /**
     * 验证分类编码
     */
    @GetMapping("/validate-code")
    @Operation(summary = "验证分类编码", description = "验证分类编码是否可用")
    public R<Boolean> validateCategoryCode(@RequestParam("code") String code) {
        try {
            boolean isValid = categoryService.validateCategoryCode(code);
            String message = isValid ? "分类编码可用" : "分类编码已存在";
            return R.success(message, isValid);
        } catch (Exception e) {
            log.error("验证分类编码失败", e);
            return R.error("验证分类编码失败: " + e.getMessage());
        }
    }

    /**
     * 获取分类统计信息
     */
    @GetMapping("/{id}/statistics")
    @Operation(summary = "获取分类统计信息", description = "获取分类相关的统计数据")
    public R<Map<String, Object>> getCategoryStatistics(@PathVariable Long id) {
        try {
            Map<String, Object> statistics = categoryService.getCategoryStatistics(id);
            return R.success("分类统计获取成功", statistics);
        } catch (Exception e) {
            log.error("获取分类统计失败", e);
            return R.error("获取分类统计失败: " + e.getMessage());
        }
    }

    /**
     * 导出分类结构
     */
    @GetMapping("/export")
    @Operation(summary = "导出分类结构", description = "导出分类结构到文件")
    public R<String> exportCategoryStructure(
            @RequestParam(value = "format", defaultValue = "excel") String format) {
        try {
            String filePath = categoryService.exportCategoryStructure(format);
            return R.success("分类导出成功", filePath);
        } catch (Exception e) {
            log.error("导出分类结构失败", e);
            return R.error("导出分类结构失败: " + e.getMessage());
        }
    }
}