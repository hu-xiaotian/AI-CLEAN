package com.aiclean.vo;

import com.aiclean.entity.TempDataTitleEntity;
import lombok.Data;

/**
 * 导入结果VO
 */
@Data
public class ImportResultVO {
    /**
     * 导入是否成功
     */
    private Boolean success;
    
    /**
     * 导入结果消息
     */
    private String message;
    
    /**
     * 导入的临时数据表头
     */
    private TempDataTitleEntity tempDataTitle;
    
    /**
     * 导入的数据行数
     */
    private Integer rowCount;
    
    /**
     * 导入用时（毫秒）
     */
    private Long importTime;
}