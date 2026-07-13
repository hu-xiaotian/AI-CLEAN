package com.aiclean.mapper;

import com.aiclean.entity.ReviewTaskEntity;
import com.aiclean.dto.StatusCount;
import com.aiclean.dto.AssigneeStatusCount;
import com.aiclean.dto.TaskTypeCount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 审核任务Mapper接口
 */
@Mapper
public interface ReviewTaskMapper extends BaseMapper<ReviewTaskEntity> {
    
    /**
     * 根据状态查询任务
     */
    @Select("SELECT * FROM review_task WHERE status = #{status} ORDER BY priority DESC, created_at")
    List<ReviewTaskEntity> selectByStatus(@Param("status") String status);
    
    /**
     * 根据审核人查询任务
     */
    @Select("SELECT * FROM review_task WHERE assigned_to = #{assignedTo} ORDER BY status, priority DESC, created_at")
    List<ReviewTaskEntity> selectByAssignedTo(@Param("assignedTo") String assignedTo);
    
    /**
     * 根据任务类型查询
     */
    @Select("SELECT * FROM review_task WHERE task_type = #{taskType} ORDER BY created_at DESC")
    List<ReviewTaskEntity> selectByTaskType(@Param("taskType") String taskType);
    
    /**
     * 根据实体类型和实体ID查询任务
     */
    @Select("SELECT * FROM review_task WHERE entity_type = #{entityType} AND entity_id = #{entityId}")
    List<ReviewTaskEntity> selectByEntity(@Param("entityType") String entityType, 
                                          @Param("entityId") Long entityId);
    
    /**
     * 查询待分配的任务
     */
    @Select("SELECT * FROM review_task WHERE (assigned_to IS NULL OR assigned_to = '') AND status = 'pending' ORDER BY priority DESC, created_at")
    List<ReviewTaskEntity> selectUnassignedTasks();
    
    /**
     * 查询进行中的任务
     */
    @Select("SELECT * FROM review_task WHERE status = 'in_progress' ORDER BY due_date, priority DESC")
    List<ReviewTaskEntity> selectInProgressTasks();
    
    /**
     * 分配任务给审核人
     */
    @Update("UPDATE review_task SET assigned_to = #{assignedTo}, assigned_by = #{assignedBy}, assigned_at = NOW(), status = 'assigned' WHERE id = #{id}")
    int assignTask(@Param("id") Long id, 
                   @Param("assignedTo") String assignedTo, 
                   @Param("assignedBy") String assignedBy);
    
    /**
     * 开始处理任务
     */
    @Update("UPDATE review_task SET status = 'in_progress', updated_at = NOW() WHERE id = #{id} AND assigned_to = #{assignedTo}")
    int startTask(@Param("id") Long id, @Param("assignedTo") String assignedTo);
    
    /**
     * 完成任务
     */
    @Update("UPDATE review_task SET status = 'completed', resolution = #{resolution}, resolution_comment = #{comment}, completed_by = #{completedBy}, completed_at = NOW() WHERE id = #{id}")
    int completeTask(@Param("id") Long id, 
                     @Param("resolution") String resolution, 
                     @Param("comment") String comment, 
                     @Param("completedBy") String completedBy);
    
    /**
     * 取消任务
     */
    @Update("UPDATE review_task SET status = 'cancelled', updated_at = NOW() WHERE id = #{id}")
    int cancelTask(@Param("id") Long id);
    
    /**
     * 统计各状态的任务数量
     */
    @Select("SELECT status, COUNT(*) as count FROM review_task WHERE 1=1 GROUP BY status")
    List<StatusCount> countByStatus();
    
    /**
     * 统计各审核人的任务数量
     */
    @Select("SELECT assigned_to, status, COUNT(*) as count FROM review_task WHERE 1=1 AND assigned_to IS NOT NULL GROUP BY assigned_to, status")
    List<AssigneeStatusCount> countByAssignee();
    
    /**
     * 统计各任务类型的数量
     */
    @Select("SELECT task_type, COUNT(*) as count FROM review_task WHERE 1=1 GROUP BY task_type")
    List<TaskTypeCount> countByTaskType();
    
    /**
     * 根据优先级查询任务
     */
    @Select("SELECT * FROM review_task WHERE priority = #{priority} ORDER BY created_at DESC")
    List<ReviewTaskEntity> selectByPriority(@Param("priority") String priority);
    
    /**
     * 查询即将到期的任务
     */
    @Select("SELECT * FROM review_task WHERE status NOT IN ('completed', 'cancelled') AND due_date <= DATE_ADD(NOW(), INTERVAL 24 HOUR) ORDER BY due_date")
    List<ReviewTaskEntity> selectExpiringTasks();
    
    /**
     * 批量分配任务
     */
    int assignTasksBatch(@Param("ids") List<Long> ids, 
                         @Param("assignedTo") String assignedTo, 
                         @Param("assignedBy") String assignedBy);

    /**
     * 根据原始数据表头ID物理删除关联的审核任务
     * （通过 cleaned_data → temp_data 的关联链）
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM review_task WHERE entity_type = 'cleaned_data' AND entity_id IN " +
            "(SELECT id FROM cleaned_data WHERE temp_data_id IN " +
            "(SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId}))")
    int deleteByCleanedDataTitleId(@Param("titleId") Long titleId);
    
}