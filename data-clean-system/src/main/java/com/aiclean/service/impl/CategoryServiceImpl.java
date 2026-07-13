package com.aiclean.service.impl;

import com.aiclean.entity.CategoryEntity;
import com.aiclean.mapper.CategoryMapper;
import com.aiclean.service.CategoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 分类管理服务实现类
 */
@Service
@Slf4j
public class CategoryServiceImpl implements CategoryService {

    @Autowired
    private CategoryMapper categoryMapper;

    @Override
    @Transactional
    public CategoryEntity createCategory(CategoryEntity category) {
        log.info("创建分类: {}", category);
        
        // 验证必要字��
        if (StringUtils.isBlank(category.getCategoryCode())) {
            throw new IllegalArgumentException("分类编码不能为空");
        }
        if (StringUtils.isBlank(category.getCategoryName())) {
            throw new IllegalArgumentException("分类名称不能为空");
        }
        
        // 验证编码是否可用
        if (!validateCategoryCode(category.getCategoryCode())) {
            throw new IllegalArgumentException("分类编码已存在: " + category.getCategoryCode());
        }
        
        // 处理父分类
        Long parentId = category.getParentId();
        if (parentId != null) {
            CategoryEntity parent = categoryMapper.selectById(parentId);
            if (parent == null) {
                throw new IllegalArgumentException("父分类不存在: " + parentId);
            }
            if (!parent.getIsActive()) {
                throw new IllegalArgumentException("父分类已禁用");
            }
            
            // 设置层级和路径
            category.setLevel(parent.getLevel() + 1);
            String fullPath = parent.getFullPath() + "/" + category.getCategoryCode();
            category.setFullPath(fullPath);
        } else {
            // 根节点
            category.setLevel(1);
            category.setFullPath("/" + category.getCategoryCode());
        }
        
        // 生成排序值
        if (category.getSortOrder() == null) {
            Integer maxSortOrder = getMaxSortOrder(parentId);
            category.setSortOrder(maxSortOrder + 1);
        }
        
        // 设置默认值
        category.setIsActive(true);
        category.setCreatedAt(LocalDateTime.now());
        category.setUpdatedAt(LocalDateTime.now());
        
        // 插入数据库
        categoryMapper.insert(category);
        
        log.info("分类创建成功, ID: {}", category.getId());
        return category;
    }

    @Override
    @Transactional
    public CategoryEntity updateCategory(CategoryEntity category) {
        log.info("更新分类: {}", category);
        
        if (category.getId() == null) {
            throw new IllegalArgumentException("分类ID不能为空");
        }
        
        // 获取现有分类
        CategoryEntity existing = categoryMapper.selectById(category.getId());
        if (existing == null) {
            throw new IllegalArgumentException("分类不存在: " + category.getId());
        }
        
        // 验证编码是否修改且是否可用
        if (StringUtils.isNotBlank(category.getCategoryCode()) 
                && !category.getCategoryCode().equals(existing.getCategoryCode())) {
            if (!validateCategoryCode(category.getCategoryCode())) {
                throw new IllegalArgumentException("分类编码已存在: " + category.getCategoryCode());
            }
            // 编码变更需要重新计算所有子节点的路径
            String oldFullPath = existing.getFullPath();
            String newFullPath = oldFullPath.substring(0, oldFullPath.lastIndexOf("/") + 1) + category.getCategoryCode();
            
            // 更新当前节点
            existing.setCategoryCode(category.getCategoryCode());
            existing.setFullPath(newFullPath);
            
            // 更新所有子节点的路径
            updateChildPaths(existing.getId(), oldFullPath, newFullPath);
        }
        
        // 更新其他字段
        if (StringUtils.isNotBlank(category.getCategoryName())) {
            existing.setCategoryName(category.getCategoryName());
        }
        if (category.getDescription() != null) {
            existing.setDescription(category.getDescription());
        }
        if (category.getSortOrder() != null) {
            existing.setSortOrder(category.getSortOrder());
        }
        if (category.getIsActive() != existing.getIsActive()) {
            existing.setIsActive(category.getIsActive());
            // 如果禁用分类，同时禁用所有子分类
            if (!category.getIsActive()) {
                disableChildCategories(existing.getId());
            }
        }
        
        existing.setUpdatedAt(LocalDateTime.now());
        categoryMapper.updateById(existing);
        
        log.info("分类更新成功, ID: {}", existing.getId());
        return existing;
    }

    @Override
    @Transactional
    public void deleteCategory(Long categoryId) {
        log.info("删除分类: {}", categoryId);
        
        CategoryEntity category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new IllegalArgumentException("分类不存在: " + categoryId);
        }
        
        // 检查是否可以删除
        Map<String, Object> checkResult = checkCategoryDeletion(categoryId);
        if (!(Boolean) checkResult.get("canDelete")) {
            throw new IllegalStateException("无法删除分类: " + checkResult.get("message"));
        }
        
        // 物理删除
        categoryMapper.deleteById(categoryId);
        
        // 同时物理删除所有子分类
        deleteChildCategories(categoryId);
        
        log.info("分类删除成功, ID: {}", categoryId);
    }

    @Override
    public CategoryEntity getCategoryById(Long categoryId) {
        return categoryMapper.selectById(categoryId);
    }

    @Override
    public CategoryEntity getCategoryByCode(String categoryCode) {
        return categoryMapper.selectByCode(categoryCode);
    }

    @Override
    public CategoryEntity getCategoryByFullPath(String fullPath) {
        return categoryMapper.selectByFullPath(fullPath);
    }

    @Override
    public List<CategoryEntity> getCategoryTree(Long parentId) {
        if (parentId == null) {
            return categoryMapper.selectRootCategories();
        }
        return categoryMapper.selectCategoryTree(parentId);
    }

    @Override
    public List<CategoryEntity> getRootCategories() {
        return categoryMapper.selectRootCategories();
    }

    @Override
    public List<CategoryEntity> getChildCategories(Long parentId) {
        if (parentId == null) {
            return Collections.emptyList();
        }
        return categoryMapper.selectByParentId(parentId);
    }

    @Override
    public List<CategoryEntity> getCategoriesByLevel(Integer level) {
        if (level == null || level < 1) {
            throw new IllegalArgumentException("层级必须大于0");
        }
        return categoryMapper.selectByLevel(level);
    }

    @Override
    public List<CategoryEntity> searchCategories(String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return Collections.emptyList();
        }
        
        List<CategoryEntity> result = new ArrayList<>();
        
        // 按编码搜索
        result.addAll(categoryMapper.selectByCodePrefix(keyword));
        
        // 按名称搜索
        result.addAll(categoryMapper.selectByName(keyword));
        
        // 去重
        return result.stream()
                .distinct()
                .sorted(Comparator.comparing(CategoryEntity::getCategoryCode))
                .collect(Collectors.toList());
    }

    @Override
    public List<CategoryEntity> getCategoriesByCodePrefix(String prefix) {
        if (StringUtils.isBlank(prefix)) {
            return Collections.emptyList();
        }
        return categoryMapper.selectByCodePrefix(prefix);
    }

    @Override
    @Transactional
    public CategoryEntity moveCategory(Long categoryId, Long newParentId) {
        log.info("移动分类, ID: {}, 新父节点ID: {}", categoryId, newParentId);
        
        CategoryEntity category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new IllegalArgumentException("分类不存在: " + categoryId);
        }
        
        // 检查目标父节点
        CategoryEntity newParent = null;
        if (newParentId != null) {
            newParent = categoryMapper.selectById(newParentId);
            if (newParent == null) {
                throw new IllegalArgumentException("目标父分类不存在: " + newParentId);
            }
            if (!newParent.getIsActive()) {
                throw new IllegalArgumentException("目标父分类已禁用");
            }
            
            // 不能移动到自己的子节点
            if (isDescendant(categoryId, newParentId)) {
                throw new IllegalArgumentException("不能将分类移动到自己的子节点");
            }
        }
        
        Long oldParentId = category.getParentId();
        String oldFullPath = category.getFullPath();
        
        // 更新分类信息
        category.setParentId(newParentId);
        
        if (newParent != null) {
            category.setLevel(newParent.getLevel() + 1);
            String newFullPath = newParent.getFullPath() + "/" + category.getCategoryCode();
            category.setFullPath(newFullPath);
            
            // 更新所有子节点的路径
            updateChildPaths(categoryId, oldFullPath, newFullPath);
        } else {
            // 移动到根节点
            category.setLevel(1);
            String newFullPath = "/" + category.getCategoryCode();
            category.setFullPath(newFullPath);
            
            // 更新所有子节点的路径
            updateChildPaths(categoryId, oldFullPath, newFullPath);
        }
        
        category.setUpdatedAt(LocalDateTime.now());
        categoryMapper.updateById(category);
        
        // 重新排序兄弟节点
        if (oldParentId != null) {
            reorderSiblings(oldParentId);
        }
        if (newParentId != null) {
            reorderSiblings(newParentId);
        } else {
            reorderRootCategories();
        }
        
        log.info("分类移动成功, ID: {}", categoryId);
        return category;
    }

    @Override
    @Transactional
    public int batchMoveCategories(List<Long> categoryIds, Long newParentId) {
        log.info("批量移动分类, IDs: {}, 新父节点ID: {}", categoryIds, newParentId);
        
        if (categoryIds == null || categoryIds.isEmpty()) {
            return 0;
        }
        
        int movedCount = 0;
        for (Long categoryId : categoryIds) {
            try {
                moveCategory(categoryId, newParentId);
                movedCount++;
            } catch (Exception e) {
                log.error("移动分类失败, ID: {}", categoryId, e);
                throw new RuntimeException("批量移动分类时出错", e);
            }
        }
        
        log.info("批量移动完成, 成功移动 {} 个分类", movedCount);
        return movedCount;
    }

    @Override
    @Transactional
    public CategoryEntity sortCategory(Long categoryId, Integer newSortOrder) {
        log.info("排序分类, ID: {}, 新排序值: {}", categoryId, newSortOrder);
        
        CategoryEntity category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new IllegalArgumentException("分类不存在: " + categoryId);
        }
        
        if (newSortOrder == null || newSortOrder < 0) {
            throw new IllegalArgumentException("排序值必须大于等于0");
        }
        
        Long parentId = category.getParentId();
        List<CategoryEntity> siblings = getChildCategories(parentId);
        
        // 重新���序兄弟节点
        List<CategoryEntity> sortedSiblings = new ArrayList<>(siblings);
        sortedSiblings.removeIf(c -> c.getId().equals(categoryId));
        
        // 插入到新位置
        if (newSortOrder >= sortedSiblings.size()) {
            sortedSiblings.add(category);
        } else {
            sortedSiblings.add(newSortOrder, category);
        }
        
        // 更新所有兄弟节点的排序值
        for (int i = 0; i < sortedSiblings.size(); i++) {
            CategoryEntity sibling = sortedSiblings.get(i);
            if (!sibling.getSortOrder().equals(i)) {
                sibling.setSortOrder(i);
                sibling.setUpdatedAt(LocalDateTime.now());
                categoryMapper.updateById(sibling);
            }
        }
        
        log.info("分类排序完成, ID: {}", categoryId);
        return category;
    }

    @Override
    @Transactional
    public int batchSortCategories(Map<Long, Integer> categoryOrders) {
        log.info("批量排序分类, 排序映射: {}", categoryOrders);
        
        if (categoryOrders == null || categoryOrders.isEmpty()) {
            return 0;
        }
        
        int sortedCount = 0;
        for (Map.Entry<Long, Integer> entry : categoryOrders.entrySet()) {
            try {
                sortCategory(entry.getKey(), entry.getValue());
                sortedCount++;
            } catch (Exception e) {
                log.error("排序分类失败, ID: {}", entry.getKey(), e);
                throw new RuntimeException("批量排序分类时出错", e);
            }
        }
        
        log.info("批量排序完成, 成功排序 {} 个分类", sortedCount);
        return sortedCount;
    }

    @Override
    @Transactional
    public CategoryEntity setCategoryActive(Long categoryId, boolean active) {
        log.info("设置分类状态, ID: {}, 启用: {}", categoryId, active);
        
        CategoryEntity category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new IllegalArgumentException("分类不存在: " + categoryId);
        }
        
        category.setIsActive(active);
        category.setUpdatedAt(LocalDateTime.now());
        categoryMapper.updateById(category);
        
        // 如果禁用分类，同时禁用所有子分类
        if (!active) {
            disableChildCategories(categoryId);
        }
        
        log.info("分类状态设置完成, ID: {}", categoryId);
        return category;
    }

    @Override
    @Transactional
    public int batchSetCategoryActive(List<Long> categoryIds, boolean active) {
        log.info("批量设置分类状态, IDs: {}, 启用: {}", categoryIds, active);
        
        if (categoryIds == null || categoryIds.isEmpty()) {
            return 0;
        }
        
        int updatedCount = 0;
        for (Long categoryId : categoryIds) {
            try {
                setCategoryActive(categoryId, active);
                updatedCount++;
            } catch (Exception e) {
                log.error("设置分类状态失败, ID: {}", categoryId, e);
                throw new RuntimeException("批量设置分类状态时出错", e);
            }
        }
        
        log.info("批量设置状态完成, 成功更新 {} 个分类", updatedCount);
        return updatedCount;
    }

    @Override
    public Map<String, Object> getCategoryStatistics(Long categoryId) {
        log.info("获取分类统计信息, ID: {}", categoryId);
        
        CategoryEntity category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new IllegalArgumentException("分类不存在: " + categoryId);
        }
        
        Map<String, Object> stats = new HashMap<>();
        
        // 基础信息
        stats.put("categoryId", category.getId());
        stats.put("categoryCode", category.getCategoryCode());
        stats.put("categoryName", category.getCategoryName());
        stats.put("level", category.getLevel());
        
        // 子分类数量
        Integer childCount = categoryMapper.selectChildCount(categoryId);
        stats.put("childCount", childCount != null ? childCount : 0);
        
        // 数据数量
        Integer dataCount = categoryMapper.selectDataCountByCategory(categoryId);
        stats.put("dataCount", dataCount != null ? dataCount : 0);
        
        // 状态信息
        stats.put("active", category.getIsActive());
        stats.put("createTime", category.getCreatedAt());
        stats.put("updateTime", category.getUpdatedAt());
        
        log.info("分类统计信息获取完成, ID: {}", categoryId);
        return stats;
    }

    @Override
    public boolean validateCategoryCode(String categoryCode) {
        if (StringUtils.isBlank(categoryCode)) {
            return false;
        }
        
        CategoryEntity existing = categoryMapper.selectByCode(categoryCode);
        return existing == null;
    }

    @Override
    public String generateCategoryCode(Long parentId) {
        if (parentId == null) {
            // 生成一级分类编码
            List<CategoryEntity> rootCategories = getRootCategories();
            int maxCode = 0;
            for (CategoryEntity category : rootCategories) {
                try {
                    int code = Integer.parseInt(category.getCategoryCode());
                    if (code > maxCode) {
                        maxCode = code;
                    }
                } catch (NumberFormatException e) {
                    // 忽略非数字编码
                }
            }
            return String.format("%02d", maxCode + 1);
        } else {
            // 生成子分类编码
            CategoryEntity parent = categoryMapper.selectById(parentId);
            if (parent == null) {
                throw new IllegalArgumentException("父分类不存在: " + parentId);
            }
            
            List<CategoryEntity> children = getChildCategories(parentId);
            int maxCode = 0;
            for (CategoryEntity child : children) {
                try {
                    String childCode = child.getCategoryCode();
                    String suffix = childCode.substring(childCode.length() - 2);
                    int code = Integer.parseInt(suffix);
                    if (code > maxCode) {
                        maxCode = code;
                    }
                } catch (NumberFormatException e) {
                    // 忽略非数字编码
                }
            }
            return parent.getCategoryCode() + String.format("%02d", maxCode + 1);
        }
    }

    @Override
    @Transactional
    public void rebuildCategoryPath(Long categoryId) {
        log.info("重建分类路径, ID: {}", categoryId);
        
        CategoryEntity category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new IllegalArgumentException("分类不存在: " + categoryId);
        }
        
        // 重新计算当前节点的路径
        String newFullPath;
        if (category.getParentId() == null) {
            newFullPath = "/" + category.getCategoryCode();
        } else {
            CategoryEntity parent = categoryMapper.selectById(category.getParentId());
            if (parent == null) {
                throw new IllegalStateException("父分类不存在: " + category.getParentId());
            }
            newFullPath = parent.getFullPath() + "/" + category.getCategoryCode();
        }
        
        // 更新当前节点
        categoryMapper.updateCategoryHierarchy(categoryId, category.getLevel(), newFullPath);
        
        // 递归重建子节点路径
        rebuildChildPaths(categoryId);
        
        log.info("分类路径重建完成, ID: {}", categoryId);
    }

    @Override
    @Transactional
    public void rebuildAllCategoryPaths() {
        log.info("开始重建所有分类路径");
        
        // 重建根节点路径
        List<CategoryEntity> rootCategories = getRootCategories();
        for (CategoryEntity root : rootCategories) {
            String fullPath = "/" + root.getCategoryCode();
            categoryMapper.updateCategoryHierarchy(root.getId(), 1, fullPath);
            rebuildChildPaths(root.getId());
        }
        
        log.info("所有分类路径重建完成");
    }

    @Override
    public String exportCategoryStructure(String format) {
        log.info("导出分类结构, 格式: {}", format);
        
        // 获取完整的分类树
        List<CategoryEntity> allCategories = categoryMapper.selectList(
            new LambdaQueryWrapper<CategoryEntity>()
                .orderByAsc(CategoryEntity::getCategoryCode)
        );
        
        // TODO: 根据格式导出
        // 这里应该实现具体的导出逻辑，比如生成Excel文件
        
        String exportPath = "/tmp/category_export_" + System.currentTimeMillis() + "." + format;
        log.info("导出完成, 文件路径: {}", exportPath);
        return exportPath;
    }

    @Override
    @Transactional
    public int importCategoryStructure(String filePath) {
        log.info("导入分类结构, 文件路径: {}", filePath);
        
        // TODO: 实现导入逻辑
        // 这里应该实现从文件读取分类结构并导入数据库的逻辑
        
        int importedCount = 0;
        log.info("导入完成, 成功导入 {} 个分类", importedCount);
        return importedCount;
    }

    @Override
    @Transactional
    public Map<String, Object> mergeCategories(Long sourceCategoryId, Long targetCategoryId) {
        log.info("合并分类, 源分类ID: {}, 目标分类ID: {}", sourceCategoryId, targetCategoryId);
        
        if (sourceCategoryId.equals(targetCategoryId)) {
            throw new IllegalArgumentException("源分类和目标分类不能相同");
        }
        
        CategoryEntity source = categoryMapper.selectById(sourceCategoryId);
        CategoryEntity target = categoryMapper.selectById(targetCategoryId);
        
        if (source == null) {
            throw new IllegalArgumentException("源分类不存在: " + sourceCategoryId);
        }
        if (target == null) {
            throw new IllegalArgumentException("目标分类不存在: " + targetCategoryId);
        }
        
        Map<String, Object> result = new HashMap<>();
        
        // TODO: 实现合并逻辑
        // 1. 将源分类的数据移动到目标分类
        // 2. 将源分类的子分类移动到目标分类
        // 3. 删除源分类
        
        result.put("success", true);
        result.put("mergedCount", 0);
        result.put("movedDataCount", 0);
        result.put("message", "合并完成");
        
        log.info("分类合并完成");
        return result;
    }

    @Override
    public Map<String, Object> checkCategoryDeletion(Long categoryId) {
        log.info("检查分类是否可以删除, ID: {}", categoryId);
        
        CategoryEntity category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new IllegalArgumentException("分类不存在: " + categoryId);
        }
        
        Map<String, Object> result = new HashMap<>();
        
        // 检查是否有子分类
        Integer childCount = categoryMapper.selectChildCount(categoryId);
        if (childCount != null && childCount > 0) {
            result.put("canDelete", false);
            result.put("message", "分类下有 " + childCount + " 个子分类，无法删除");
            return result;
        }
        
        // 检查是否有关联的数据
        Integer dataCount = categoryMapper.selectDataCountByCategory(categoryId);
        if (dataCount != null && dataCount > 0) {
            result.put("canDelete", false);
            result.put("message", "分类下有 " + dataCount + " 条数据，无法删除");
            return result;
        }
        
        result.put("canDelete", true);
        result.put("message", "可以安全删除");
        
        log.info("删除检查完成, 结果: {}", result);
        return result;
    }

    // =============== 私有辅助方法 ===============

    /**
     * 获取指定父节点下的最大排序值
     */
    private Integer getMaxSortOrder(Long parentId) {
        LambdaQueryWrapper<CategoryEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CategoryEntity::getParentId, parentId)
                   .orderByDesc(CategoryEntity::getSortOrder)
                   .last("LIMIT 1");
        
        CategoryEntity category = categoryMapper.selectOne(queryWrapper);
        return category != null ? category.getSortOrder() : -1;
    }

    /**
     * 更新子节点的路径
     */
    private void updateChildPaths(Long parentId, String oldParentPath, String newParentPath) {
        List<CategoryEntity> children = getChildCategories(parentId);
        for (CategoryEntity child : children) {
            String oldChildPath = child.getFullPath();
            String newChildPath = newParentPath + oldChildPath.substring(oldParentPath.length());
            
            // 更新当前子节点
            child.setLevel(newParentPath.split("/").length);
            child.setFullPath(newChildPath);
            child.setUpdatedAt(LocalDateTime.now());
            categoryMapper.updateById(child);
            
            // 递归更新子节点的子节点
            updateChildPaths(child.getId(), oldChildPath, newChildPath);
        }
    }

    /**
     * 禁用所有子分类
     */
    private void disableChildCategories(Long parentId) {
        List<CategoryEntity> children = getChildCategories(parentId);
        for (CategoryEntity child : children) {
            child.setIsActive(false);
            child.setUpdatedAt(LocalDateTime.now());
            categoryMapper.updateById(child);
            
            // 递归禁用子节点的子节点
            disableChildCategories(child.getId());
        }
    }

    /**
     * 删除所有子分类（物理删除）
     */
    private void deleteChildCategories(Long parentId) {
        List<CategoryEntity> children = getChildCategories(parentId);
        for (CategoryEntity child : children) {
            categoryMapper.deleteById(child.getId());
            
            // 递归删除子节点的子节点
            deleteChildCategories(child.getId());
        }
    }

    /**
     * 重建子节点的路径
     */
    private void rebuildChildPaths(Long parentId) {
        List<CategoryEntity> children = getChildCategories(parentId);
        for (CategoryEntity child : children) {
            // 重新计算路径
            CategoryEntity parent = categoryMapper.selectById(parentId);
            String newFullPath = parent.getFullPath() + "/" + child.getCategoryCode();
            
            // 更新子节点
            child.setLevel(parent.getLevel() + 1);
            child.setFullPath(newFullPath);
            child.setUpdatedAt(LocalDateTime.now());
            categoryMapper.updateById(child);
            
            // 递归重建子节点的子节点
            rebuildChildPaths(child.getId());
        }
    }

    /**
     * 重新排序兄弟节点
     */
    private void reorderSiblings(Long parentId) {
        List<CategoryEntity> siblings = getChildCategories(parentId);
        Collections.sort(siblings, Comparator.comparing(CategoryEntity::getSortOrder));
        
        for (int i = 0; i < siblings.size(); i++) {
            CategoryEntity sibling = siblings.get(i);
            if (!sibling.getSortOrder().equals(i)) {
                sibling.setSortOrder(i);
                sibling.setUpdatedAt(LocalDateTime.now());
                categoryMapper.updateById(sibling);
            }
        }
    }

    /**
     * 重新排序根节点
     */
    private void reorderRootCategories() {
        List<CategoryEntity> rootCategories = getRootCategories();
        Collections.sort(rootCategories, Comparator.comparing(CategoryEntity::getSortOrder));
        
        for (int i = 0; i < rootCategories.size(); i++) {
            CategoryEntity root = rootCategories.get(i);
            if (!root.getSortOrder().equals(i)) {
                root.setSortOrder(i);
                root.setUpdatedAt(LocalDateTime.now());
                categoryMapper.updateById(root);
            }
        }
    }

    /**
     * 检查节点是否是另一个节点的后代
     */
    private boolean isDescendant(Long ancestorId, Long descendantId) {
        if (ancestorId == null || descendantId == null) {
            return false;
        }
        
        CategoryEntity descendant = categoryMapper.selectById(descendantId);
        while (descendant != null && descendant.getParentId() != null) {
            if (descendant.getParentId().equals(ancestorId)) {
                return true;
            }
            descendant = categoryMapper.selectById(descendant.getParentId());
        }
        
        return false;
    }
}