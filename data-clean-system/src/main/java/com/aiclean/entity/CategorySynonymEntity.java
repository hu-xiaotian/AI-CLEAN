package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 分类同义词映射实体
 * 用于维护人工维护的"别名 -> 标准分类"映射，提升分类匹配的召回率。
 * synonym 为原始别名，synonymNorm 为归一化后的值（用于容错匹配）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("category_synonym")
public class CategorySynonymEntity extends BaseEntity {

    /**
     * 对应 main_data_category.id（可指向任意层级，匹配后再解析到三级）
     */
    private Long categoryId;

    /**
     * 原始同义词（别名）
     * 注意：synonym 是达梦保留字，数据库列名使用 synonym_name，
     * 属性名用 synonymName 以避免 MyBatis-Plus 生成 synonym 别名触发保留字错误
     */
    @TableField("synonym_name")
    private String synonymName;

    /**
     * 归一化后的同义词（去空格/标点/大小写），用于匹配
     */
    private String synonymNorm;

    /**
     * 备注说明
     */
    private String description;
}
