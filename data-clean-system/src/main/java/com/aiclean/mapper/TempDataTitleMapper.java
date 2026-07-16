package com.aiclean.mapper;

import com.aiclean.entity.TempDataTitleEntity;
import com.aiclean.dto.StatusCount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 原始数据表头Mapper接口
 */
@Mapper
public interface TempDataTitleMapper extends BaseMapper<TempDataTitleEntity> {
    
    /**
     * 根据文件名查询
     */
    @Select("SELECT * FROM temp_data_title WHERE file_name = #{fileName}")
    List<TempDataTitleEntity> selectByFileName(@Param("fileName") String fileName);
    
    /**
     * 根据状态查询
     */
    @Select("SELECT * FROM temp_data_title WHERE status = #{status} ORDER BY created_at DESC")
    List<TempDataTitleEntity> selectByStatus(@Param("status") String status);
    
    /**
     * 根据ID更新状态
     */
    @Update("UPDATE temp_data_title SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);
    
    /**
     * 根据ID更新处理进度
     */
    @Update("UPDATE temp_data_title SET total_rows = #{totalRows}, updated_at = NOW() WHERE id = #{id}")
    int updateProgress(@Param("id") Long id, @Param("totalRows") Integer totalRows);
    
    /**
     * 获取最近的文件列表
     */
    @Select("SELECT * FROM temp_data_title WHERE 1=1 ORDER BY created_at DESC LIMIT #{limit}")
    List<TempDataTitleEntity> selectRecent(@Param("limit") Integer limit);
    
    /**
     * 统计各状态的数量
     */
    @Select("SELECT status, COUNT(*) as count FROM temp_data_title WHERE 1=1 GROUP BY status")
    List<StatusCount> countByStatus();
    
    /**
     * 查询指定时间范围内的文件
     */
    @Select("SELECT * FROM temp_data_title WHERE created_at BETWEEN #{startTime} AND #{endTime} ORDER BY created_at DESC")
    List<TempDataTitleEntity> selectByTimeRange(@Param("startTime") String startTime, 
                                                @Param("endTime") String endTime);

    /**
     * 统计全部文件的总导入行数
     */
    @Select("SELECT COALESCE(SUM(total_rows), 0) FROM temp_data_title")
    Integer sumTotalRows();

    /**
     * 统计指定文件的总导入行数
     */
    @Select("SELECT COALESCE(SUM(total_rows), 0) FROM temp_data_title WHERE id = #{titleId}")
    Integer sumTotalRowsByTitleId(@Param("titleId") Long titleId);

}