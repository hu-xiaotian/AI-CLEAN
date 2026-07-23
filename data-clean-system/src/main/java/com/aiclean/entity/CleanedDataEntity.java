package com.aiclean.entity;

import com.aiclean.entity.enums.DataStatus;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 清洗后数据实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cleaned_data")
public class CleanedDataEntity extends BaseEntity {
    
    /**
     * 分类ID
     */
    private Long categoryId;
    
    /**
     * 分类编码
     */
    private String categoryCode;
    
    /**
     * 分类层级
     */
    private Integer categoryLevel;
    
    /**
     * 分类完整路径
     */
    private String categoryFullPath;

    /**
     * 分类匹配来源（SYNONYM/NAME_EXACT/NAME_FUZZY/CODE_EXACT/CODE_PREFIX/EXTRA_NAME/SEMANTIC/UNMATCHED）
     */
    private String matchSource;

    /**
     * 分类匹配置信度（0~1）
     */
    private Double matchConfidence;
    
    /**
     * 原始数据ID
     */
    private Long tempDataId;
    
    /**
     * 使用的标准字段ID
     */
    private Long standardTitleId;
    
    /**
     * 物料代码
     */
    private String materialCode;
    
    /**
     * 物料名称
     */
    private String materialName;
    
    /**
     * 规格
     */
    private String specification;
    
    /**
     * 技术标准
     */
    private String technicalStandard;
    
    /**
     * 牌号
     */
    private String grade;
    
    /**
     * 计量单位
     */
    private String unit;
    
    /**
     * 导入时指定的"属性拆分列"原始文本（全描述），用于 AI 打分匹配，而非仅用解析出的属性名称列
     */
    private String fullDescription;
    
    /**
     * 原始行数据指纹（数据血缘/去重/增量清洗），按关键字段计算
     */
    private String sourceRowHash;
    
    /**
     * 是否重复数据（同文件内指纹相同）
     */
    private Integer isDuplicate;
    
    /**
     * 状态
     */
    private DataStatus status;
    
    /**
     * 质量评分（0-100）
     */
    private Double qualityScore;
    
    /**
     * AI 辅助分类理由描述（启用 AI 辅助评分时，大模型对分类合理性的说明）
     */
    private String aiReason;
    
    /**
     * 完整性评分
     */
    private Double completenessScore;
    
    /**
     * 准确性评分
     */
    private Double accuracyScore;
    
    /**
     * 审核意见
     */
    private String reviewComment;
    
    /**
     * 审核人
     */
    private String reviewedBy;
    
    /**
     * 审核时间
     */
    private LocalDateTime reviewedAt;
    
    /**
     * 导出批次ID
     */
    private Long exportBatchId;
    
    /**
     * 导出时间
     */
    private LocalDateTime exportedAt;
    
    /**
     * 分类名称（非数据库字段）
     */
    @TableField(exist = false)
    private String categoryName;
    
    /**
     * 原始数据（非数据库字段）
     */
    @TableField(exist = false)
    private TempDataEntity tempData;
    
    /**
     * 标准字段（非数据库字段）
     */
    @TableField(exist = false)
    private StandardFieldEntity standardField;
    
    /**
     * 是否可以导出
     */
    public boolean isExportable() {
        return DataStatus.isExportable(status);
    }
    
    /**
     * 是否需要审核
     */
    public boolean needsReview() {
        return DataStatus.needsReview(status);
    }
    
    /**
     * 计算数据完整性
     */
    public double calculateCompleteness() {
        int filled = 0;
        int total = 0;
        
        if (materialCode != null && !materialCode.trim().isEmpty()) filled++;
        total++;
        
        if (materialName != null && !materialName.trim().isEmpty()) filled++;
        total++;
        
        if (specification != null && !specification.trim().isEmpty()) filled++;
        total++;
        
        if (technicalStandard != null && !technicalStandard.trim().isEmpty()) filled++;
        total++;
        
        if (grade != null && !grade.trim().isEmpty()) filled++;
        total++;
        
        if (unit != null && !unit.trim().isEmpty()) filled++;
        total++;
        
        return total > 0 ? (double) filled / total * 100 : 0;
    }
}