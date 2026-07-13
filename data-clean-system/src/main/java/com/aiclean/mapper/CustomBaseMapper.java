package com.aiclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 自定义基础Mapper接口
 * @param <T> 实体类型
 */
public interface CustomBaseMapper<T> extends com.baomidou.mybatisplus.core.mapper.BaseMapper<T> {
    
    /**
     * 批量插入（自定义方法）
     */
    int insertBatch(T entity);
    
    /**
     * 批量更新（自定义方法）
     */
    int updateBatch(T entity);
}