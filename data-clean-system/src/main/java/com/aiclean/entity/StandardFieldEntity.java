package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 标准字段定义实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("standard_field_definition")
public class StandardFieldEntity extends BaseEntity {
    
    /**
     * 分类ID
     */
    private Long categoryId;
    
    /**
     * 字段名（英文）
     */
    private String fieldName;
    
    /**
     * 显示名（中文）
     */
    private String displayName;
    
    /**
     * 数据类型（string, int, float, date, datetime）
     */
    private String dataType;
    
    /**
     * 是否必填
     */
    private Boolean isRequired;
    
    /**
     * 最小长度
     */
    private Integer minLength;
    
    /**
     * 最大长度
     */
    private Integer maxLength;
    
    /**
     * 最小值
     */
    private String minValue;
    
    /**
     * 最大值
     */
    private String maxValue;
    
    /**
     * 正则表达式模式
     */
    private String pattern;
    
    /**
     * 允许的值列表（JSON数组）
     */
    private String allowedValues;
    
    /**
     * 显示顺序
     */
    private Integer displayOrder;
    
    /**
     * 是否显示
     */
    private Boolean isVisible;
    
    /**
     * 是否可编辑
     */
    private Boolean isEditable;
    
    /**
     * 默认值
     */
    private String defaultValue;
    
    /**
     * 提示信息
     */
    private String hint;
    
    /**
     * 依赖字段
     */
    private String dependsOnField;
    
    /**
     * 依赖值
     */
    private String dependsOnValue;
    
    /**
     * 分类名称（非数据库字段）
     */
    @TableField(exist = false)
    private String categoryName;
    
    /**
     * 分类编码（非数据库字段）
     */
    @TableField(exist = false)
    private String categoryCode;
    
    /**
     * 验证是否通过
     */
    public boolean validate(String value) {
        if (value == null) {
            return !isRequired;  // 为空时，必填字段验证失败
        }
        
        // 长度验证
        if (minLength != null && value.length() < minLength) {
            return false;
        }
        if (maxLength != null && value.length() > maxLength) {
            return false;
        }
        
        // 模式验证
        if (pattern != null && !pattern.isEmpty()) {
            if (!value.matches(pattern)) {
                return false;
            }
        }
        
        // 值列表验证
        if (allowedValues != null && !allowedValues.isEmpty()) {
            // TODO: 解析JSON数组并验证
        }
        
        return true;
    }
}