package com.aiclean.mapper;

import com.aiclean.entity.CleanedDataEntity;
import com.aiclean.dto.StatusCount;
import com.aiclean.dto.CategoryDataCount;
import com.aiclean.model.SearchCondition;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 清洗后数据Mapper接口
 */
@Mapper
public interface CleanedDataMapper extends BaseMapper<CleanedDataEntity> {
    
    /**
     * 批量插入清洗后数据
     */
    @Insert("<script>" +
            "INSERT INTO cleaned_data (temp_data_id, category_id, category_code, category_level, " +
            "category_full_path, material_code, material_name, specification, unit, " +
            "quality_score, status, created_at, updated_at) VALUES " +
            "<foreach item='item' collection='list' separator=','>" +
            "(#{item.tempDataId}, #{item.categoryId}, #{item.categoryCode}, #{item.categoryLevel}, " +
            "#{item.categoryFullPath}, #{item.materialCode}, #{item.materialName}, #{item.specification}, #{item.unit}, " +
            "#{item.qualityScore}, #{item.status}, #{item.createdAt}, #{item.updatedAt})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<CleanedDataEntity> list);
    
    /**
     * 根据分类ID查询数据
     */
    @Select("SELECT * FROM cleaned_data WHERE category_id = #{categoryId} ORDER BY created_at DESC")
    List<CleanedDataEntity> selectByCategoryId(@Param("categoryId") Long categoryId);
    
    /**
     * 根据分类编码查询数据
     */
    @Select("SELECT * FROM cleaned_data WHERE category_code = #{categoryCode} ORDER BY created_at DESC")
    List<CleanedDataEntity> selectByCategoryCode(@Param("categoryCode") String categoryCode);
    
    /**
     * 根据分类路径查询数据（支持层级查询）
     */
    @Select("SELECT * FROM cleaned_data WHERE category_full_path LIKE CONCAT(#{pathPrefix}, '%') ORDER BY category_code, material_code")
    List<CleanedDataEntity> selectByCategoryPath(@Param("pathPrefix") String pathPrefix);
    
    /**
     * 根据状态查询数据
     */
    @Select("SELECT * FROM cleaned_data WHERE status = #{status} ORDER BY created_at DESC")
    List<CleanedDataEntity> selectByStatus(@Param("status") String status);
    
    /**
     * 根据原始数据ID查询
     */
    @Select("SELECT * FROM cleaned_data WHERE temp_data_id = #{tempDataId} ORDER BY id DESC LIMIT 1")
    CleanedDataEntity selectByTempDataId(@Param("tempDataId") Long tempDataId);
    
    /**
     * 根据物料代码查询
     */
    @Select("SELECT * FROM cleaned_data WHERE material_code = #{materialCode}")
    List<CleanedDataEntity> selectByMaterialCode(@Param("materialCode") String materialCode);
    
    /**
     * 根据物料名称模糊查询
     */
    @Select("SELECT * FROM cleaned_data WHERE material_name LIKE CONCAT('%', #{materialName}, '%')")
    List<CleanedDataEntity> selectByMaterialName(@Param("materialName") String materialName);
    
    /**
     * 根据质量评分范围查询
     */
    @Select("SELECT * FROM cleaned_data WHERE quality_score BETWEEN #{minScore} AND #{maxScore} ORDER BY quality_score DESC")
    List<CleanedDataEntity> selectByQualityScoreRange(@Param("minScore") Double minScore, 
                                                       @Param("maxScore") Double maxScore);
    
    /**
     * 根据导出批次ID查询
     */
    @Select("SELECT * FROM cleaned_data WHERE export_batch_id = #{batchId} ORDER BY exported_at DESC")
    List<CleanedDataEntity> selectByExportBatchId(@Param("batchId") Long batchId);
    
    /**
     * 多条件查询数据
     */
    List<CleanedDataEntity> searchByConditions(@Param("condition") SearchCondition condition);
    
    /**
     * 统计多条件查询结果数量
     */
    Long countByConditions(@Param("condition") SearchCondition condition);
    
    /**
     * 批量更新状态
     */
    int updateBatchStatus(@Param("ids") List<Long> ids, 
                          @Param("status") String status,
                          @Param("reviewedBy") String reviewedBy,
                          @Param("reviewComment") String reviewComment);
    
    /**
     * 批量设置导出批次
     */
    int updateExportBatch(@Param("ids") List<Long> ids, 
                          @Param("batchId") Long batchId);
    
    /**
     * 根据分类ID批量更新分类信息
     */
    @Update("UPDATE cleaned_data SET category_code = #{categoryCode}, category_level = #{level}, category_full_path = #{fullPath} WHERE category_id = #{categoryId}")
    int updateCategoryInfo(@Param("categoryId") Long categoryId,
                           @Param("categoryCode") String categoryCode,
                           @Param("level") Integer level,
                           @Param("fullPath") String fullPath);
    
    /**
     * 统计各分类的数据数量
     */
    @Select("SELECT cd.category_id, cd.category_code, mdc.category_name, COUNT(*) as count FROM cleaned_data cd LEFT JOIN main_data_category mdc ON cd.category_id = mdc.id WHERE 1=1 GROUP BY cd.category_id, cd.category_code, mdc.category_name")
    List<CategoryDataCount> countByCategory();
    
    /**
     * 统计各状态的数据数量
     */
    @Select("SELECT status, COUNT(*) as count FROM cleaned_data WHERE 1=1 GROUP BY status")
    List<StatusCount> countByStatus();
    
    /**
     * 获取可导出的数据（状态为可导出或已审核通过）
     */
    @Select("SELECT * FROM cleaned_data WHERE status IN ('export_ready', 'approved') AND (export_batch_id IS NULL OR export_batch_id = 0) ORDER BY category_code, material_code")
    List<CleanedDataEntity> selectExportableData();
    
    /**
     * 根据ID列表查询数据
     */
    @Select("<script>" +
            "SELECT * FROM cleaned_data WHERE id IN " +
            "<foreach item='id' collection='ids' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            " AND 1=1" +
            "</script>")
    List<CleanedDataEntity> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 根据表头ID物理删除所有关联的清洗数据
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM cleaned_data WHERE temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId})")
    int deleteByTitleId(@Param("titleId") Long titleId);

    /**
     * 根据原始数据表头ID查询清洗后的数据
     */
    @Select("SELECT * FROM cleaned_data WHERE temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId}) LIMIT 1")
    CleanedDataEntity selectByTempDataTitleId(@Param("titleId") Long titleId);

    /**
     * 根据原始数据表头ID查询所有清洗后的数据
     */
    @Select("SELECT * FROM cleaned_data WHERE temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId})")
    List<CleanedDataEntity> selectAllByTempDataTitleId(@Param("titleId") Long titleId);

    /**
     * 根据原始数据表头ID查询清洗数据中所有不重复的分类编码
     */
    @Select("SELECT category_code FROM cleaned_data WHERE temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId}) AND category_code IS NOT NULL AND category_code != '' GROUP BY category_code ORDER BY MIN(id)")
    List<String> selectDistinctCategoryCodesByTitleId(@Param("titleId") Long titleId);

    /**
     * 查询未映射的清洗数据（有清洗结果但无对应result_data，关联temp_data获取原始数据列）
     */
    List<CleanedDataEntity> selectUnmappedByTitleId(@Param("titleId") Long titleId);

    /**
     * 统计未映射的清洗数据数量
     */
    @Select("SELECT COUNT(*) FROM cleaned_data cd " +
            "INNER JOIN temp_data td ON cd.temp_data_id = td.id " +
            "LEFT JOIN result_data rd ON cd.id = rd.cleaned_data_id " +
            "WHERE td.temp_data_title_id = #{titleId} AND rd.id IS NULL")
    Long countUnmappedByTitleId(@Param("titleId") Long titleId);

    /**
     * 统计某数据文件下的清洗数据数量
     */
    @Select("SELECT COUNT(*) FROM cleaned_data WHERE temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId})")
    Long countByTitleId(@Param("titleId") Long titleId);

    /**
     * 统计已成功填充到结果数据的清洗记录数（按数据文件）
     */
    @Select("SELECT COUNT(*) FROM cleaned_data cd INNER JOIN result_data rd ON cd.id = rd.cleaned_data_id WHERE cd.temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId})")
    Long countFilledByTitleId(@Param("titleId") Long titleId);

    /**
     * 统计已成功填充到结果数据的清洗记录数（全部）
     */
    @Select("SELECT COUNT(*) FROM cleaned_data cd INNER JOIN result_data rd ON cd.id = rd.cleaned_data_id")
    Long countFilled();

    /**
     * 统计分类不匹配（match_source = UNMATCHED）的清洗记录数（按数据文件）
     */
    @Select("SELECT COUNT(*) FROM cleaned_data WHERE match_source = 'UNMATCHED' AND temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId})")
    Long countUnmatchedByTitleId(@Param("titleId") Long titleId);

    /**
     * 统计分类不匹配（match_source = UNMATCHED）的清洗记录数（全部）
     */
    @Select("SELECT COUNT(*) FROM cleaned_data WHERE match_source = 'UNMATCHED'")
    Long countUnmatched();

    /**
     * 统计某数据文件下各状态的数量
     */
    @Select("SELECT status, COUNT(*) as count FROM cleaned_data WHERE temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId}) GROUP BY status")
    List<StatusCount> countByStatusByTitleId(@Param("titleId") Long titleId);

    /**
     * 统计某数据文件下各分类的数量（Top 10）
     */
    @Select("SELECT cd.category_id, cd.category_code, mdc.category_name, COUNT(*) as count FROM cleaned_data cd LEFT JOIN main_data_category mdc ON cd.category_id = mdc.id WHERE cd.temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId}) GROUP BY cd.category_id, cd.category_code, mdc.category_name ORDER BY count DESC LIMIT 10")
    List<CategoryDataCount> countByCategoryByTitleId(@Param("titleId") Long titleId);

    /**
     * 统计各分类的数量（Top 10，全部）
     */
    @Select("SELECT cd.category_id, cd.category_code, mdc.category_name, COUNT(*) as count FROM cleaned_data cd LEFT JOIN main_data_category mdc ON cd.category_id = mdc.id GROUP BY cd.category_id, cd.category_code, mdc.category_name ORDER BY count DESC LIMIT 10")
    List<CategoryDataCount> countByCategoryTop();

    /**
     * 统计某数据文件下平均质量评分
     */
    @Select("SELECT AVG(quality_score) FROM cleaned_data WHERE quality_score IS NOT NULL AND temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId})")
    Double avgScoreByTitleId(@Param("titleId") Long titleId);

    /**
     * 统计全部平均质量评分
     */
    @Select("SELECT AVG(quality_score) FROM cleaned_data WHERE quality_score IS NOT NULL")
    Double avgScore();

    /**
     * 查询某数据文件下分类不匹配的清洗数据
     */
    @Select("SELECT * FROM cleaned_data WHERE match_source = 'UNMATCHED' AND temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId}) ORDER BY id")
    List<CleanedDataEntity> selectUnmatchedByTitleId(@Param("titleId") Long titleId);

    /**
     * 查询全部分类不匹配的清洗数据（限制数量，避免过大）
     */
    @Select("SELECT * FROM cleaned_data WHERE match_source = 'UNMATCHED' ORDER BY id LIMIT 500")
    List<CleanedDataEntity> selectUnmatchedAll();

}