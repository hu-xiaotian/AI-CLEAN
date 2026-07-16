package com.aiclean.mapper;

import com.aiclean.entity.FailedResultDataEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 填充失败结果数据 Mapper
 */
@Mapper
public interface FailedResultDataMapper extends BaseMapper<FailedResultDataEntity> {

    @Insert("<script>" +
            "INSERT INTO failed_result_data (temp_data_id, cleaned_data_id, category_code, reason, raw_data, status) VALUES " +
            "<foreach item='item' collection='list' separator=','>" +
            "(#{item.tempDataId}, #{item.cleanedDataId}, #{item.categoryCode}, #{item.reason}, #{item.rawData}, #{item.status})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<FailedResultDataEntity> list);

    /**
     * 按数据文件（temp_data_title_id）查询其填充失败记录
     */
    @Select("SELECT f.* FROM failed_result_data f " +
            "INNER JOIN temp_data td ON f.temp_data_id = td.id " +
            "WHERE td.temp_data_title_id = #{titleId} ORDER BY f.id")
    List<FailedResultDataEntity> selectByTitleId(@Param("titleId") Long titleId);

    /**
     * 物理删除某数据文件下的所有填充失败记录
     */
    @Delete("DELETE FROM failed_result_data WHERE temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId})")
    int deleteByTitleId(@Param("titleId") Long titleId);

    /**
     * 查询全部填充失败记录
     */
    @Select("SELECT * FROM failed_result_data ORDER BY id")
    List<FailedResultDataEntity> selectAll();

    /**
     * 统计某数据文件下的填充失败记录数
     */
    @Select("SELECT COUNT(*) FROM failed_result_data f INNER JOIN temp_data td ON f.temp_data_id = td.id WHERE td.temp_data_title_id = #{titleId}")
    Long countByTitleId(@Param("titleId") Long titleId);
}
