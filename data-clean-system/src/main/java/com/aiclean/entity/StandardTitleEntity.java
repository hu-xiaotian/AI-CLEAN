package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 标准字段表头实体类
 * 每个分类编码对应一组标准字段定义
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("standard_title")
public class StandardTitleEntity extends BaseEntity {

    @TableField("category_code")
    private String categoryCode;

    /**
     * 分类名称（来自 main_data_category 表，非标准表头自身字段）
     */
    @TableField(exist = false)
    private String categoryName;

    @TableField("col_title_1")
    private String colTitle1;
    @TableField("col_title_2")
    private String colTitle2;
    @TableField("col_title_3")
    private String colTitle3;
    @TableField("col_title_4")
    private String colTitle4;
    @TableField("col_title_5")
    private String colTitle5;
    @TableField("col_title_6")
    private String colTitle6;
    @TableField("col_title_7")
    private String colTitle7;
    @TableField("col_title_8")
    private String colTitle8;
    @TableField("col_title_9")
    private String colTitle9;
    @TableField("col_title_10")
    private String colTitle10;
    @TableField("col_title_11")
    private String colTitle11;
    @TableField("col_title_12")
    private String colTitle12;
    @TableField("col_title_13")
    private String colTitle13;
    @TableField("col_title_14")
    private String colTitle14;
    @TableField("col_title_15")
    private String colTitle15;
    @TableField("col_title_16")
    private String colTitle16;
    @TableField("col_title_17")
    private String colTitle17;
    @TableField("col_title_18")
    private String colTitle18;
    @TableField("col_title_19")
    private String colTitle19;
    @TableField("col_title_20")
    private String colTitle20;

    @TableField("col_title_1_is_must")
    private Boolean colTitle1IsMust;
    @TableField("col_title_2_is_must")
    private Boolean colTitle2IsMust;
    @TableField("col_title_3_is_must")
    private Boolean colTitle3IsMust;
    @TableField("col_title_4_is_must")
    private Boolean colTitle4IsMust;
    @TableField("col_title_5_is_must")
    private Boolean colTitle5IsMust;
    @TableField("col_title_6_is_must")
    private Boolean colTitle6IsMust;
    @TableField("col_title_7_is_must")
    private Boolean colTitle7IsMust;
    @TableField("col_title_8_is_must")
    private Boolean colTitle8IsMust;
    @TableField("col_title_9_is_must")
    private Boolean colTitle9IsMust;
    @TableField("col_title_10_is_must")
    private Boolean colTitle10IsMust;
    @TableField("col_title_11_is_must")
    private Boolean colTitle11IsMust;
    @TableField("col_title_12_is_must")
    private Boolean colTitle12IsMust;
    @TableField("col_title_13_is_must")
    private Boolean colTitle13IsMust;
    @TableField("col_title_14_is_must")
    private Boolean colTitle14IsMust;
    @TableField("col_title_15_is_must")
    private Boolean colTitle15IsMust;
    @TableField("col_title_16_is_must")
    private Boolean colTitle16IsMust;
    @TableField("col_title_17_is_must")
    private Boolean colTitle17IsMust;
    @TableField("col_title_18_is_must")
    private Boolean colTitle18IsMust;
    @TableField("col_title_19_is_must")
    private Boolean colTitle19IsMust;
    @TableField("col_title_20_is_must")
    private Boolean colTitle20IsMust;

    public String getColTitle(int index) {
        switch (index) {
            case 1: return colTitle1;
            case 2: return colTitle2;
            case 3: return colTitle3;
            case 4: return colTitle4;
            case 5: return colTitle5;
            case 6: return colTitle6;
            case 7: return colTitle7;
            case 8: return colTitle8;
            case 9: return colTitle9;
            case 10: return colTitle10;
            case 11: return colTitle11;
            case 12: return colTitle12;
            case 13: return colTitle13;
            case 14: return colTitle14;
            case 15: return colTitle15;
            case 16: return colTitle16;
            case 17: return colTitle17;
            case 18: return colTitle18;
            case 19: return colTitle19;
            case 20: return colTitle20;
            default: return null;
        }
    }

    public Boolean isMust(int index) {
        switch (index) {
            case 1: return colTitle1IsMust;
            case 2: return colTitle2IsMust;
            case 3: return colTitle3IsMust;
            case 4: return colTitle4IsMust;
            case 5: return colTitle5IsMust;
            case 6: return colTitle6IsMust;
            case 7: return colTitle7IsMust;
            case 8: return colTitle8IsMust;
            case 9: return colTitle9IsMust;
            case 10: return colTitle10IsMust;
            case 11: return colTitle11IsMust;
            case 12: return colTitle12IsMust;
            case 13: return colTitle13IsMust;
            case 14: return colTitle14IsMust;
            case 15: return colTitle15IsMust;
            case 16: return colTitle16IsMust;
            case 17: return colTitle17IsMust;
            case 18: return colTitle18IsMust;
            case 19: return colTitle19IsMust;
            case 20: return colTitle20IsMust;
            default: return false;
        }
    }
}
