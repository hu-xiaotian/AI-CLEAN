package com.aiclean.dto;

import lombok.Data;

/**
 * 任务类型统计DTO
 */
@Data
public class TaskTypeCount {
    /**
     * 任务类型
     */
    private String taskType;
    
    /**
     * 数量
     */
    private Integer count;
}