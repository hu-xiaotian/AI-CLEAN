package com.aiclean.model;

import com.aiclean.entity.enums.DataStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 查询条件模型
 */
@Data
public class SearchCondition {
    
    /**
     * 分类编码列表
     */
    private List<String> categoryCodes;
    
    /**
     * 分类层级
     */
    private Integer categoryLevel;
    
    /**
     * 分类路径前缀
     */
    private String categoryPathPrefix;
    
    /**
     * 物料代码（模糊查询）
     */
    private String materialCode;
    
    /**
     * 物料名称（模糊查询）
     */
    private String materialName;
    
    /**
     * 规格（模糊查询）
     */
    private String specification;
    
    /**
     * 技术标准（模糊查询）
     */
    private String technicalStandard;
    
    /**
     * 状态列表
     */
    private List<DataStatus> statuses;
    
    /**
     * 质量评分最小值
     */
    private Double qualityScoreMin;
    
    /**
     * 质量评分最大值
     */
    private Double qualityScoreMax;
    
    /**
     * 创建时间起始
     */
    private LocalDateTime createdStart;
    
    /**
     * 创建时间结束
     */
    private LocalDateTime createdEnd;
    
    /**
     * 审核时间起始
     */
    private LocalDateTime reviewedStart;
    
    /**
     * 审核时间结束
     */
    private LocalDateTime reviewedEnd;
    
    /**
     * 导出时间起始
     */
    private LocalDateTime exportedStart;
    
    /**
     * 导出时间结束
     */
    private LocalDateTime exportedEnd;
    
    /**
     * 是否可导出
     */
    private Boolean exportable;
    
    /**
     * 是否已审核
     */
    private Boolean reviewed;
    
    /**
     * 是否包含子分类
     */
    private Boolean includeSubCategories;
    
    /**
     * 排序字段
     */
    private String sortBy;
    
    /**
     * 排序方向（asc, desc）
     */
    private String sortOrder;
    
    /**
     * 页码（从1开始）
     */
    private Integer page;
    
    /**
     * 每页大小
     */
    private Integer pageSize;
    
    /**
     * 标准字段表头ID
     */
    private Long standardTitleId;
    
    /**
     * 是否查询所有数据
     */
    private Boolean queryAll;
    
    /**
     * 获取排序字段
     */
    public String getSortField() {
        if (sortBy == null || sortBy.isEmpty()) {
            return "created_at";
        }
        
        // 映射前端字段名到数据库字段名
        switch (sortBy) {
            case "materialCode": return "material_code";
            case "materialName": return "material_name";
            case "categoryCode": return "category_code";
            case "qualityScore": return "quality_score";
            case "createdAt": return "created_at";
            case "reviewedAt": return "reviewed_at";
            case "exportedAt": return "exported_at";
            default: return sortBy;
        }
    }
    
    /**
     * 获取排序方向
     */
    public String getSortDirection() {
        if (sortOrder == null || sortOrder.isEmpty()) {
            return "desc";
        }
        return "desc".equalsIgnoreCase(sortOrder) ? "desc" : "asc";
    }
    
    /**
     * 获取偏移量
     */
    public Integer getOffset() {
        if (page == null || page < 1) {
            page = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = 20;
        }
        return (page - 1) * pageSize;
    }
    
    /**
     * 获取限制条数
     */
    public Integer getLimit() {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        if (queryAll != null && queryAll) {
            return Integer.MAX_VALUE;
        }
        return pageSize;
    }
    
    /**
     * 是否有查询条件
     */
    public boolean hasConditions() {
        return (categoryCodes != null && !categoryCodes.isEmpty())
                || categoryLevel != null
                || categoryPathPrefix != null
                || materialCode != null
                || materialName != null
                || specification != null
                || technicalStandard != null
                || (statuses != null && !statuses.isEmpty())
                || qualityScoreMin != null
                || qualityScoreMax != null
                || createdStart != null
                || createdEnd != null
                || reviewedStart != null
                || reviewedEnd != null
                || exportedStart != null
                || exportedEnd != null
                || exportable != null
                || reviewed != null;
    }
}