package com.aiclean.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 相似物料推荐结果载体。
 * 在 CleanedDataEntity 基础上附加相似度评分、命中关键词与推荐理由，
 * 供 AI 对话框「相似物料推荐」能力返回并渲染来源卡片。
 */
@Data
public class SimilarMaterialDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 物料代码 */
    private String materialCode;

    /** 物料名称 */
    private String materialName;

    /** 规格 */
    private String specification;

    /** 牌号 */
    private String grade;

    /** 计量单位 */
    private String unit;

    /** 分类编码 */
    private String categoryCode;

    /** 分类名称 */
    private String categoryName;

    /** 分类完整路径 */
    private String categoryFullPath;

    /** 相似度评分（0~1，查询词命中覆盖率） */
    private Double similarityScore;

    /** 命中的查询关键词（用于解释“为何相似”） */
    private String matchedTokens;

    /** 推荐理由 */
    private String reason;
}
