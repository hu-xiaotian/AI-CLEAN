package com.aiclean.dto;

import lombok.Data;

/**
 * 状态统计DTO
 */
@Data
public class StatusCount {
    /**
     * 状态
     */
    private String status;
    
    /**
     * 数量
     */
    private Integer count;
}