package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据文件-标准字段表头关联实体
 * 在数据清洗/结果填充时记录每个数据文件关联的标准字段表头，
 * 供结果数据下拉框按数据文件快速查询，避免每次拉取整张标准表头后在客户端过滤。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("title_standard_title")
public class TitleStandardTitleEntity extends BaseEntity {

    /**
     * 数据文件ID（temp_data_title.id）
     */
    private Long tempDataTitleId;

    /**
     * 标准字段表头ID（standard_title.id）
     */
    private Long standardTitleId;
}
