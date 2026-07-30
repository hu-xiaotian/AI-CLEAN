package com.aiclean.externalclean.mapper;

import com.aiclean.externalclean.entity.ExternalCleanTaskEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

/**
 * 外部清洗任务 Mapper
 */
public interface ExternalCleanTaskMapper extends BaseMapper<ExternalCleanTaskEntity> {

    /**
     * 查询处于 processing 且尚未收到回调的任务（超时判断在 Java 端按 submitted_at 计算，保持数据库无关）
     */
    List<ExternalCleanTaskEntity> selectProcessingWithoutCallback();
}
