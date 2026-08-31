package com.aiclean.externalclean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 外部清洗服务任务进展查询响应（与 /api/v1/clean/{task_id} 对齐）。
 * 上游使用 snake_case 字段名，故声明 SnakeCase 命名策略。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExternalProgressResponse {

    private String taskId;
    private String status; // pending / processing / completed / failed / cancelled
    private Stats stats;
    private String results;
    private String error;

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stats {
        private Integer totalRows;
        /** 已分类行数（v1.3 新增） */
        private Integer classifiedRows;
        private Integer processedRows;
        private Integer highConfidence;
        private Integer mediumConfidence;
        private Integer lowConfidence;
        /** 置信度累加值（v1.3 新增） */
        private Double confidenceSum;
        private Double estimatedAccuracy;
    }
}
