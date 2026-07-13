package com.aiclean.vo;

import com.aiclean.entity.enums.DataStatus;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 数据查询条件视图对象
 */
@Data
public class DataQueryVO {
    /**
     * 分类ID列表
     */
    private List<Long> categoryIds;

    /**
     * 分类编码列表
     */
    private List<String> categoryCodes;

    /**
     * 状态列表
     */
    private List<DataStatus> statuses;

    /**
     * 关键词搜索（模糊匹配名称、编码、描述等）
     */
    private String keyword;

    /**
     * 开始日期（创建时间）
     */
    private String startDate;

    /**
     * 结束日期（创建时间）
     */
    private String endDate;

    /**
     * 字段条件映射
     * key: 字段名, value: 字段值
     */
    private Map<String, Object> fieldConditions;

    /**
     * 排序字段
     * key: 字段名, value: asc/desc
     */
    private Map<String, String> sortFields;

    /**
     * 分页页码
     */
    private Integer page;

    /**
     * 每页大小
     */
    private Integer size;

    /**
     * 是否包含已禁用分类的数据
     */
    private Boolean includeDisabledCategories;

    /**
     * 质量分数范围
     */
    private QualityScoreRange qualityScoreRange;

    /**
     * 导出字段列表
     */
    private List<String> exportFields;

    /**
     * 是否只统计数量
     */
    private Boolean countOnly;

    /**
     * 是否包含子分类数据
     */
    private Boolean includeChildren;

    /**
     * 数据质量分数范围
     */
    @Data
    public static class QualityScoreRange {
        private Double minScore;
        private Double maxScore;

        public boolean isValid() {
            return minScore != null && maxScore != null && minScore <= maxScore;
        }
    }

    /**
     * 验证查询条件
     */
    public boolean isValid() {
        if (page != null && page <= 0) {
            return false;
        }
        if (size != null && (size <= 0 || size > 1000)) {
            return false;
        }
        if (qualityScoreRange != null && !qualityScoreRange.isValid()) {
            return false;
        }
        return true;
    }

    /**
     * 获取有效的页码
     */
    public int getValidPage() {
        return page == null || page <= 0 ? 1 : page;
    }

    /**
     * 获取有效的每页大小
     */
    public int getValidSize() {
        if (size == null || size <= 0) {
            return 20;
        }
        return Math.min(size, 1000);
    }

    /**
     * 获取偏移量
     */
    public int getOffset() {
        return (getValidPage() - 1) * getValidSize();
    }

    /**
     * 是否包含分类条件
     */
    public boolean hasCategoryConditions() {
        return (categoryIds != null && !categoryIds.isEmpty()) ||
                (categoryCodes != null && !categoryCodes.isEmpty());
    }

    /**
     * 是否包含状态条件
     */
    public boolean hasStatusConditions() {
        return statuses != null && !statuses.isEmpty();
    }

    /**
     * 是否包含日期范围条件
     */
    public boolean hasDateRangeConditions() {
        return startDate != null || endDate != null;
    }

    /**
     * 是否包含字段条件
     */
    public boolean hasFieldConditions() {
        return fieldConditions != null && !fieldConditions.isEmpty();
    }

    /**
     * 是否包含排序条件
     */
    public boolean hasSortConditions() {
        return sortFields != null && !sortFields.isEmpty();
    }

    /**
     * 是否包含关键词搜索
     */
    public boolean hasKeywordSearch() {
        return keyword != null && !keyword.trim().isEmpty();
    }

    /**
     * 是否包含质量分数条件
     */
    public boolean hasQualityScoreConditions() {
        return qualityScoreRange != null && qualityScoreRange.isValid();
    }
}