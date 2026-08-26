package com.aiclean.mapper;

import com.aiclean.entity.CategoryVectorEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 标准分类语义向量 Mapper。
 * <p>
 * 对 category_vector 表做增删改查，配合语义知识库（SemanticCategoryLibrary）实现向量的持久化存储与加载。
 */
@Mapper
public interface CategoryVectorMapper extends BaseMapper<CategoryVectorEntity> {
}
