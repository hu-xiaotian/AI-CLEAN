package com.aiclean.mapper;

import com.aiclean.entity.CategoryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 分类Mapper接口
 */
@Mapper
public interface CategoryMapper extends BaseMapper<CategoryEntity> {
    
    /**
     * 根据父ID查询分类
     */
    @Select("SELECT * FROM main_data_category WHERE parent_id = #{parentId} ORDER BY sort_order, category_code")
    List<CategoryEntity> selectByParentId(@Param("parentId") Long parentId);
    
    /**
     * 根据层级查询分类
     */
    @Select("SELECT * FROM main_data_category WHERE level = #{level} ORDER BY category_code")
    List<CategoryEntity> selectByLevel(@Param("level") Integer level);
    
    /**
     * 根据完整路径查询分类
     */
    @Select("SELECT * FROM main_data_category WHERE full_path = #{fullPath}")
    CategoryEntity selectByFullPath(@Param("fullPath") String fullPath);
    
    /**
     * 根据分类编码查询分类
     */
    @Select("SELECT * FROM main_data_category WHERE category_code = #{categoryCode}")
    CategoryEntity selectByCode(@Param("categoryCode") String categoryCode);
    
    /**
     * 查询子分类数量
     */
    @Select("SELECT COUNT(*) FROM main_data_category WHERE parent_id = #{id}")
    Integer selectChildCount(@Param("id") Long id);
    
    /**
     * 查询分类树（包含所有子节点）
     */
    List<CategoryEntity> selectCategoryTree(@Param("parentId") Long parentId);
    
    /**
     * 根据分类编码前缀查询
     */
    @Select("SELECT * FROM main_data_category WHERE category_code LIKE CONCAT(#{prefix}, '%') ORDER BY category_code")
    List<CategoryEntity> selectByCodePrefix(@Param("prefix") String prefix);
    
    /**
     * 根据分类名称模糊查询
     */
    @Select("SELECT * FROM main_data_category WHERE category_name LIKE CONCAT('%', #{name}, '%')")
    List<CategoryEntity> selectByName(@Param("name") String name);
    
    /**
     * 获取所有一级分类
     */
    @Select("SELECT * FROM main_data_category WHERE parent_id IS NULL ORDER BY sort_order, category_code")
    List<CategoryEntity> selectRootCategories();
    
    /**
     * 获取分类下的数据数量
     */
    @Select("SELECT COUNT(*) FROM cleaned_data WHERE category_id = #{categoryId}")
    Integer selectDataCountByCategory(@Param("categoryId") Long categoryId);
    
    /**
     * 批量更新分类层级和路径
     */
    int updateCategoryHierarchy(@Param("id") Long id, 
                                @Param("level") Integer level, 
                                @Param("fullPath") String fullPath);
}