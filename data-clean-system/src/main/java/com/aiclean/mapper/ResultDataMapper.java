package com.aiclean.mapper;

import com.aiclean.entity.ResultDataEntity;
import com.aiclean.model.SearchCondition;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 清洗结果数据Mapper
 */
@Mapper
public interface ResultDataMapper extends BaseMapper<ResultDataEntity> {

    @Select("SELECT * FROM result_data WHERE standard_title_id = #{titleId} ORDER BY id")
    List<ResultDataEntity> selectByStandardTitleId(@Param("titleId") Long titleId);

    @Select("SELECT * FROM result_data WHERE status = #{status} ORDER BY id")
    List<ResultDataEntity> selectByStatus(@Param("status") String status);

    @Select("SELECT * FROM result_data WHERE temp_data_id = #{tempDataId} ORDER BY id DESC LIMIT 1")
    ResultDataEntity selectByTempDataId(@Param("tempDataId") Long tempDataId);

    @Insert("<script>" +
            "INSERT INTO result_data (standard_title_id, temp_data_id, cleaned_data_id, " +
            "col1,col2,col3,col4,col5,col6,col7,col8,col9,col10," +
            "col11,col12,col13,col14,col15,col16,col17,col18,col19,col20) VALUES " +
            "<foreach item='item' collection='list' separator=','>" +
            "(#{item.standardTitleId}, #{item.tempDataId}, #{item.cleanedDataId}, " +
            "#{item.col1},#{item.col2},#{item.col3},#{item.col4},#{item.col5}," +
            "#{item.col6},#{item.col7},#{item.col8},#{item.col9},#{item.col10}," +
            "#{item.col11},#{item.col12},#{item.col13},#{item.col14},#{item.col15}," +
            "#{item.col16},#{item.col17},#{item.col18},#{item.col19},#{item.col20})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<ResultDataEntity> list);

    List<ResultDataEntity> searchByConditions(@Param("condition") SearchCondition condition);

    Long countByConditions(@Param("condition") SearchCondition condition);

    /**
     * 根据表头ID物理删除所有关联的结果数据
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM result_data WHERE temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId})")
    int deleteByTitleId(@Param("titleId") Long titleId);

    /**
     * 派生某数据文件下已填充结果数据所关联的标准字段表头ID集合（用于关联表的懒回填）
     */
    @Select("SELECT DISTINCT standard_title_id FROM result_data rd " +
            "INNER JOIN temp_data td ON rd.temp_data_id = td.id " +
            "WHERE td.temp_data_title_id = #{tempDataTitleId} AND rd.standard_title_id IS NOT NULL")
    List<Long> selectStandardTitleIdsByTitle(@Param("tempDataTitleId") Long tempDataTitleId);

    /**
     * 根据标准字段表头ID + 数据表头ID 物理删除关联的结果数据
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM result_data WHERE standard_title_id = #{standardTitleId} AND temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId})")
    int deleteByStandardAndTitle(@Param("standardTitleId") Long standardTitleId, @Param("titleId") Long titleId);

    /**
     * 物理删除某数据文件下“未匹配标准表头”（standard_title_id IS NULL）的结果数据
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM result_data WHERE standard_title_id IS NULL AND temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId})")
    int deleteByTitleIdAndNullStandard(@Param("titleId") Long titleId);
}
