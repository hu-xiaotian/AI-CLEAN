package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
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
     * 主键ID（由 MyBatis-Plus 雪花算法生成，避免依赖数据库自增/IDENTITY，
     * 解决达梦等数据库中该列未配置自增时的非空约束报错）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 数据文件ID（temp_data_title.id）
     */
    private Long tempDataTitleId;

    /**
     * 标准字段表头ID（standard_title.id）
     */
    private Long standardTitleId;
}
