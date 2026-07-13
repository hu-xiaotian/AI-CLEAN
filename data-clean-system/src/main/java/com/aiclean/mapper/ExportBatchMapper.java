package com.aiclean.mapper;

import com.aiclean.entity.ExportBatchEntity;
import com.aiclean.dto.StatusCount;
import com.aiclean.dto.FormatCount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 导出批次Mapper接口
 */
@Mapper
public interface ExportBatchMapper extends BaseMapper<ExportBatchEntity> {
    
    /**
     * 根据状态查询导出批次
     */
    @Select("SELECT * FROM export_batch WHERE status = #{status} ORDER BY created_at DESC")
    List<ExportBatchEntity> selectByStatus(@Param("status") String status);
    
    /**
     * 根据导出人查询
     */
    @Select("SELECT * FROM export_batch WHERE exported_by = #{exportedBy} ORDER BY created_at DESC")
    List<ExportBatchEntity> selectByExportedBy(@Param("exportedBy") String exportedBy);
    
    /**
     * 根据导出类型查询
     */
    @Select("SELECT * FROM export_batch WHERE export_type = #{exportType} ORDER BY created_at DESC")
    List<ExportBatchEntity> selectByExportType(@Param("exportType") String exportType);
    
    /**
     * 更新导出状态
     */
    @Update("UPDATE export_batch SET status = #{status}, error_message = #{errorMessage}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, 
                     @Param("status") String status, 
                     @Param("errorMessage") String errorMessage);
    
    /**
     * 更新导出进度
     */
    @Update("UPDATE export_batch SET exported_records = #{exportedRecords}, updated_at = NOW() WHERE id = #{id}")
    int updateProgress(@Param("id") Long id, @Param("exportedRecords") Integer exportedRecords);
    
    /**
     * 更新文件信息
     */
    @Update("UPDATE export_batch SET file_name = #{fileName}, file_path = #{filePath}, file_size = #{fileSize}, exported_at = NOW(), status = 'completed' WHERE id = #{id}")
    int updateFileInfo(@Param("id") Long id,
                       @Param("fileName") String fileName,
                       @Param("filePath") String filePath,
                       @Param("fileSize") Long fileSize);
    
    /**
     * 获取最近的导出批次
     */
    @Select("SELECT * FROM export_batch WHERE 1=1 ORDER BY created_at DESC LIMIT #{limit}")
    List<ExportBatchEntity> selectRecent(@Param("limit") Integer limit);
    
    /**
     * 根据时间范围查询导出批次
     */
    @Select("SELECT * FROM export_batch WHERE exported_at BETWEEN #{startTime} AND #{endTime} ORDER BY exported_at DESC")
    List<ExportBatchEntity> selectByTimeRange(@Param("startTime") String startTime, 
                                              @Param("endTime") String endTime);
    
    /**
     * 统计各状态的数量
     */
    @Select("SELECT status, COUNT(*) as count FROM export_batch WHERE 1=1 GROUP BY status")
    List<StatusCount> countByStatus();
    
    /**
     * 统计各导出格式的数量
     */
    @Select("SELECT format, COUNT(*) as count FROM export_batch WHERE 1=1 GROUP BY format")
    List<FormatCount> countByFormat();
    
    /**
     * 查询处理中的导出任务
     */
    @Select("SELECT * FROM export_batch WHERE status = 'processing' ORDER BY created_at")
    List<ExportBatchEntity> selectProcessingTasks();
    
    /**
     * 根据分类ID查询导出批次
     */
    List<ExportBatchEntity> selectByCategoryIds(@Param("categoryIds") List<Long> categoryIds);
    
}