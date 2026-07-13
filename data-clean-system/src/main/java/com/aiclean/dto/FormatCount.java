package com.aiclean.dto;

import lombok.Data;

/**
 * 格式统计DTO
 */
@Data
public class FormatCount {
    /**
     * 格式
     */
    private String format;
    
    /**
     * 数量
     */
    private Integer count;
}