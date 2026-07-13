package com.aiclean.service;

import com.aiclean.entity.CategoryEntity;

import java.util.List;

/**
 * 分类管理服务接口
 */
public interface CategoryService {
    
    /**
     * 创建分类
     * @param category 分类信息
     * @return 创建的分类
     */
    CategoryEntity createCategory(CategoryEntity category);
    
    /**
     * 更新分类
     * @param category 分类信息
     * @return 更新后的分类
     */
    CategoryEntity updateCategory(CategoryEntity category);
    
    /**
     * 删除分类
     * @param categoryId 分类ID
     */
    void deleteCategory(Long categoryId);
    
    /**
     * 根据ID获取分类
     * @param categoryId 分类ID
     * @return 分类信息
     */
    CategoryEntity getCategoryById(Long categoryId);
    
    /**
     * 根据编码获取分类
     * @param categoryCode 分类编码
     * @return 分类信息
     */
    CategoryEntity getCategoryByCode(String categoryCode);
    
    /**
     * 根据完整路径获取分类
     * @param fullPath 完整路径
     * @return 分类信息
     */
    CategoryEntity getCategoryByFullPath(String fullPath);
    
    /**
     * 获取分类树
     * @param parentId 父节点ID（null表示根节点）
     * @return 分类树
     */
    List<CategoryEntity> getCategoryTree(Long parentId);
    
    /**
     * 获取所有一级分类
     * @return 一级分类列表
     */
    List<CategoryEntity> getRootCategories();
    
    /**
     * 获取子分类列表
     * @param parentId 父节点ID
     * @return 子分类列表
     */
    List<CategoryEntity> getChildCategories(Long parentId);
    
    /**
     * 根据层级获取分类
     * @param level 层级（1,2,3...）
     * @return 分类列表
     */
    List<CategoryEntity> getCategoriesByLevel(Integer level);
    
    /**
     * 搜索分类
     * @param keyword 关键字（编码或名称）
     * @return 分类列表
     */
    List<CategoryEntity> searchCategories(String keyword);
    
    /**
     * 根据编码前缀获取分类
     * @param prefix 编码前缀
     * @return 分类列表
     */
    List<CategoryEntity> getCategoriesByCodePrefix(String prefix);
    
    /**
     * 移动分类
     * @param categoryId 分类ID
     * @param newParentId 新的父节点ID
     * @return 移动后的分类
     */
    CategoryEntity moveCategory(Long categoryId, Long newParentId);
    
    /**
     * 批量移动分类
     * @param categoryIds 分类ID列表
     * @param newParentId 新的父节点ID
     * @return 移动数量
     */
    int batchMoveCategories(List<Long> categoryIds, Long newParentId);
    
    /**
     * 排序分类
     * @param categoryId 分类ID
     * @param newSortOrder 新的排序值
     * @return 排序后的分类
     */
    CategoryEntity sortCategory(Long categoryId, Integer newSortOrder);
    
    /**
     * 批量排序分类
     * @param categoryOrders 分类排序映射（分类ID -> 排序值）
     * @return 排序数量
     */
    int batchSortCategories(java.util.Map<Long, Integer> categoryOrders);
    
    /**
     * 启用/禁用分类
     * @param categoryId 分类ID
     * @param active 是否启用
     * @return 更新后的分类
     */
    CategoryEntity setCategoryActive(Long categoryId, boolean active);
    
    /**
     * 批量启用/禁用分类
     * @param categoryIds 分类ID列表
     * @param active 是否启用
     * @return 更新数量
     */
    int batchSetCategoryActive(List<Long> categoryIds, boolean active);
    
    /**
     * 获取分类统计信息
     * @param categoryId 分类ID
     * @return 统计信息
     */
    java.util.Map<String, Object> getCategoryStatistics(Long categoryId);
    
    /**
     * 验证分类编码是否可用
     * @param categoryCode 分类编码
     * @return 是否可用
     */
    boolean validateCategoryCode(String categoryCode);
    
    /**
     * 生成分类编码
     * @param parentId 父节点ID
     * @return 生成的编码
     */
    String generateCategoryCode(Long parentId);
    
    /**
     * 重建分类路径
     * @param categoryId 分类ID
     */
    void rebuildCategoryPath(Long categoryId);
    
    /**
     * 重建所有分类路径
     */
    void rebuildAllCategoryPaths();
    
    /**
     * 导出分类结构
     * @param format 导出格式（excel, json, xml）
     * @return 导出文件路径
     */
    String exportCategoryStructure(String format);
    
    /**
     * 导入分类结构
     * @param filePath 文件路径
     * @return 导入的分类数量
     */
    int importCategoryStructure(String filePath);
    
    /**
     * 合并分类
     * @param sourceCategoryId 源分类ID
     * @param targetCategoryId 目标分类ID
     * @return 合并结果
     */
    java.util.Map<String, Object> mergeCategories(Long sourceCategoryId, Long targetCategoryId);
    
    /**
     * 检查分类是否可删除
     * @param categoryId 分类ID
     * @return 检查结果
     */
    java.util.Map<String, Object> checkCategoryDeletion(Long categoryId);
}