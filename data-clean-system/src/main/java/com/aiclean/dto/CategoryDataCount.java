package com.aiclean.dto;

import lombok.Data;

/**
 * 分类数据统计DTO
 */
@Data
public class CategoryDataCount {
    /**
     * 分类ID
     */
    private Long categoryId;
    
    /**
     * 分类编码
     */
    private String categoryCode;
    
    /**
     * 分类名称
     */
    private String categoryName;
    
    /**
     * 数量
     */
    private Integer count;
}