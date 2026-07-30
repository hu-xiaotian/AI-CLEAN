package com.aiclean.externalclean.dto;

import lombok.Data;

import java.util.Map;

/**
 * 行修正请求
 */
@Data
public class TaskRowCorrectRequest {

    /** 修正后的分类编码 */
    private String correctedCategoryCode;

    /** 修正后的分类名称 */
    private String correctedCategoryName;

    /** 修正后的分类路径 */
    private String correctedCategoryPath;

    /** 修正后的属性键值对（列名->值） */
    private Map<String, String> correctedAttrs;

    /** 修正备注 */
    private String comment;
}
