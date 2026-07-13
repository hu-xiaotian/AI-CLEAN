package com.aiclean.vo;

import com.aiclean.entity.CategoryEntity;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类树视图对象
 */
@Data
public class CategoryTreeVO {
    /**
     * 分类ID
     */
    private Long id;

    /**
     * 分类编码
     */
    private String code;

    /**
     * 分类名称
     */
    private String name;

    /**
     * 层级
     */
    private Integer level;

    /**
     * 父分类ID
     */
    private Long parentId;

    /**
     * 完整路径
     */
    private String fullPath;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 排序号
     */
    private Integer sortOrder;

    /**
     * 描述
     */
    private String description;

    /**
     * 子分类列表
     */
    private List<CategoryTreeVO> children;

    /**
     * 数据统计
     */
    private DataStatistics statistics;

    /**
     * 数据统计信息
     */
    @Data
    public static class DataStatistics {
        /**
         * 总数据量
         */
        private Integer totalDataCount;

        /**
         * 有效数据量
         */
        private Integer validDataCount;

        /**
         * 待审核数据量
         */
        private Integer pendingReviewCount;

        /**
         * 最近更新时间
         */
        private String lastUpdateTime;
    }

    /**
     * 从实体类创建树节点
     */
    public static CategoryTreeVO fromEntity(CategoryEntity entity) {
        CategoryTreeVO vo = new CategoryTreeVO();
        vo.setId(entity.getId());
        vo.setCode(entity.getCategoryCode());
        vo.setName(entity.getCategoryName());
        vo.setLevel(entity.getLevel());
        vo.setParentId(entity.getParentId());
        vo.setFullPath(entity.getFullPath());
        vo.setEnabled(entity.getIsActive());
        vo.setSortOrder(entity.getSortOrder());
        vo.setDescription(entity.getDescription());
        vo.setChildren(new ArrayList<>());
        return vo;
    }

    /**
     * 添加子节点
     */
    public void addChild(CategoryTreeVO child) {
        if (this.children == null) {
            this.children = new ArrayList<>();
        }
        this.children.add(child);
    }

    /**
     * 判断是否为叶子节点
     */
    public boolean isLeaf() {
        return children == null || children.isEmpty();
    }

    /**
     * 判断是否有子节点
     */
    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }
}