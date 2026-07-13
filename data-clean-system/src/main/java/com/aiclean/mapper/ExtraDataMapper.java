package com.aiclean.mapper;

import com.aiclean.entity.ExtraDataEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 补充数据Mapper
 */
@Mapper
public interface ExtraDataMapper extends BaseMapper<ExtraDataEntity> {

    @Select("SELECT * FROM extra_data WHERE extra_data_title_id = #{titleId}")
    List<ExtraDataEntity> selectByExtraDataTitleId(@Param("titleId") Long titleId);

    @Select("SELECT * FROM extra_data WHERE temp_data_id = #{tempDataId} AND extra_data_title_id = #{extraDataTitleId} ORDER BY id DESC LIMIT 1")
    ExtraDataEntity selectByTempDataId(@Param("tempDataId") Long tempDataId, @Param("extraDataTitleId") Long extraDataTitleId);

    @Insert("<script>" +
            "INSERT INTO extra_data (extra_data_title_id, temp_data_id, " +
            "col1, col2, col3, col4, col5, col6, col7, col8, col9, col10, " +
            "col11, col12, col13, col14, col15, col16, col17, col18, col19, col20) VALUES " +
            "<foreach item='item' collection='list' separator=','>" +
            "(#{item.extraDataTitleId}, #{item.tempDataId}, " +
            "#{item.col1}, #{item.col2}, #{item.col3}, #{item.col4}, #{item.col5}, " +
            "#{item.col6}, #{item.col7}, #{item.col8}, #{item.col9}, #{item.col10}, " +
            "#{item.col11}, #{item.col12}, #{item.col13}, #{item.col14}, #{item.col15}, " +
            "#{item.col16}, #{item.col17}, #{item.col18}, #{item.col19}, #{item.col20})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<ExtraDataEntity> list);

    /**
     * 根据补充表头ID物理删除所有补充数据
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM extra_data WHERE extra_data_title_id = #{extraDataTitleId}")
    int deleteByExtraDataTitleId(@Param("extraDataTitleId") Long extraDataTitleId);

    /**
     * 根据原始数据表头ID物理删除所有关联的补充数据（通过extra_data_title子查询）
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM extra_data WHERE extra_data_title_id IN (SELECT id FROM extra_data_title WHERE temp_data_title_id = #{titleId})")
    int deleteByTempDataTitleId(@Param("titleId") Long titleId);
}
