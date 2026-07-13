package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 分类实体类（支持层级）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("main_data_category")
public class CategoryEntity extends BaseEntity {
    
    /**
     * 分类编码（如：10, 1001, 100101）
     */
    private String categoryCode;
    
    /**
     * 分类名称
     */
    private String categoryName;
    
    /**
     * 父节点ID
     */
    private Long parentId;
    
    /**
     * 层级（1:一级, 2:二级, 3:三级...）
     */
    private Integer level;
    
    /**
     * 完整路径（如: 10/1001/100101）
     */
    private String fullPath;
    
    /**
     * 计量单位
     */
    private String unit;
    
    /**
     * 说明
     */
    private String description;
    
    /**
     * 排序
     */
    private Integer sortOrder;
    
    /**
     * 是否启用
     */
    private Boolean isActive;
    
    /**
     * 旧分类编码1（用于数据匹配）
     */
    @TableField("old_code_1")
    private String oldCode1;
    
    /**
     * 旧分类名称1
     */
    @TableField("old_name_1")
    private String oldName1;
    
    /**
     * 旧分类编码2
     */
    @TableField("old_code_2")
    private String oldCode2;
    
    /**
     * 旧分类名称2
     */
    @TableField("old_name_2")
    private String oldName2;
    
    /**
     * 旧分类编码3
     */
    @TableField("old_code_3")
    private String oldCode3;
    
    /**
     * 旧分类名称3
     */
    @TableField("old_name_3")
    private String oldName3;
    
    /**
     * 旧分类编码4
     */
    @TableField("old_code_4")
    private String oldCode4;
    
    /**
     * 旧分类名称4
     */
    @TableField("old_name_4")
    private String oldName4;
    
    /**
     * 旧分类编码5
     */
    @TableField("old_code_5")
    private String oldCode5;
    
    /**
     * 旧分类名称5
     */
    @TableField("old_name_5")
    private String oldName5;
    
    /**
     * 父分类名称（非数据库字段）
     */
    @TableField(exist = false)
    private String parentName;
    
    /**
     * 子分类数量（非数据库字段）
     */
    @TableField(exist = false)
    private Integer childCount;
    
    /**
     * 数据数量（非数据库字段）
     */
    @TableField(exist = false)
    private Integer dataCount;
    
    /**
     * 构建完整路径
     */
    public String buildFullPath(CategoryEntity parent) {
        if (parent == null) {
            return categoryCode;
        }
        return parent.getFullPath() + "/" + categoryCode;
    }
    
    /**
     * 判断是否是一级分类
     */
    public boolean isRoot() {
        return parentId == null || parentId == 0;
    }
    
    /**
     * 判断是否是叶子节点
     */
    public boolean isLeaf() {
        return childCount == null || childCount == 0;
    }
}