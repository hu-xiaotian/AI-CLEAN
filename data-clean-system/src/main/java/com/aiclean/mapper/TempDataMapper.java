package com.aiclean.mapper;

import com.aiclean.entity.TempDataEntity;
import com.aiclean.dto.StatusCount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 原始数据Mapper接口
 */
@Mapper
public interface TempDataMapper extends BaseMapper<TempDataEntity> {
    
    /**
     * 根据表头ID查询数据
     */
    @Select("SELECT * FROM temp_data WHERE temp_data_title_id = #{titleId} ORDER BY row_index")
    List<TempDataEntity> selectByTitleId(@Param("titleId") Long titleId);
    
    /**
     * 根据表头ID和状态查询数据
     */
    @Select("SELECT * FROM temp_data WHERE temp_data_title_id = #{titleId} AND status = #{status}")
    List<TempDataEntity> selectByTitleIdAndStatus(@Param("titleId") Long titleId, 
                                                  @Param("status") String status);
    
    /**
     * 根据表头ID分页查询数据
     */
    @Select("SELECT * FROM temp_data WHERE temp_data_title_id = #{titleId} ORDER BY row_index LIMIT #{offset}, #{limit}")
    List<TempDataEntity> selectByTitleIdPage(@Param("titleId") Long titleId,
                                             @Param("offset") Integer offset,
                                             @Param("limit") Integer limit);
    
    /**
     * 根据表头ID批量更新状态
     */
    @Update("UPDATE temp_data SET status = #{status}, updated_at = NOW() WHERE temp_data_title_id = #{titleId}")
    int updateStatusByTitleId(@Param("titleId") Long titleId, @Param("status") String status);
    
    /**
     * 根据ID批量更新状态
     */
    int updateBatchStatus(@Param("ids") List<Long> ids, @Param("status") String status);
    
    /**
     * 根据表头ID统计各状态数量
     */
    @Select("SELECT status, COUNT(*) as count FROM temp_data WHERE temp_data_title_id = #{titleId} GROUP BY status")
    List<StatusCount> countStatusByTitleId(@Param("titleId") Long titleId);
    
    /**
     * 根据表头ID和行号查询数据
     */
    @Select("SELECT * FROM temp_data WHERE temp_data_title_id = #{titleId} AND row_index = #{rowIndex}")
    TempDataEntity selectByTitleIdAndRowIndex(@Param("titleId") Long titleId, 
                                              @Param("rowIndex") Integer rowIndex);
    
    /**
     * 批量插入原始数据
     */
    int insertBatch(@Param("list") List<TempDataEntity> list);
    
    /**
     * 根据表头ID物理删除数据
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM temp_data WHERE temp_data_title_id = #{titleId}")
    int deleteByTitleId(@Param("titleId") Long titleId);
    
    /**
     * 查询未处理的数据
     */
    @Select("SELECT * FROM temp_data WHERE status = 'pending' ORDER BY created_at LIMIT #{limit}")
    List<TempDataEntity> selectPendingData(@Param("limit") Integer limit);
    
    /**
     * 根据表头ID获取最大行号
     */
    @Select("SELECT MAX(row_index) FROM temp_data WHERE temp_data_title_id = #{titleId}")
    Integer selectMaxRowIndex(@Param("titleId") Long titleId);
    
    /**
     * 根据表头ID统计总行数
     */
    @Select("SELECT COUNT(*) FROM temp_data WHERE temp_data_title_id = #{titleId}")
    Integer countByTitleId(@Param("titleId") Long titleId);
}