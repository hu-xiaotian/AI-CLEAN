package com.aiclean.dto;

import lombok.Data;

/**
 * 分配人状态统计DTO
 */
@Data
public class AssigneeStatusCount {
    /**
     * 分配人ID
     */
    private String assigneeId;
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 数量
     */
    private Integer count;
}