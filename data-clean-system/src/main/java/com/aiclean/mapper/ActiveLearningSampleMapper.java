package com.aiclean.mapper;

import com.aiclean.entity.ActiveLearningSampleEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * 主动学习样本 Mapper
 */
@Mapper
public interface ActiveLearningSampleMapper extends BaseMapper<ActiveLearningSampleEntity> {

    /** 统计某数据文件下低置信样本（LOW_CONFIDENCE）数量 */
    @Select("SELECT COUNT(*) FROM active_learning_sample s WHERE s.sample_type = 'LOW_CONFIDENCE' " +
            "AND s.entity_id IN (SELECT cd.id FROM cleaned_data cd WHERE cd.temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId}))")
    Long countLowConfidenceByTitleId(@Param("titleId") Long titleId);

    /** 查询某数据文件下低置信样本（LOW_CONFIDENCE） */
    @Select("SELECT s.* FROM active_learning_sample s WHERE s.sample_type = 'LOW_CONFIDENCE' " +
            "AND s.entity_id IN (SELECT cd.id FROM cleaned_data cd WHERE cd.temp_data_id IN (SELECT id FROM temp_data WHERE temp_data_title_id = #{titleId})) " +
            "ORDER BY s.id LIMIT 500")
    List<ActiveLearningSampleEntity> selectLowConfidenceByTitleId(@Param("titleId") Long titleId);

    /** 统计全部低置信样本数量 */
    @Select("SELECT COUNT(*) FROM active_learning_sample WHERE sample_type = 'LOW_CONFIDENCE'")
    Long countLowConfidence();

    /** 查询全部低置信样本（限制数量） */
    @Select("SELECT * FROM active_learning_sample WHERE sample_type = 'LOW_CONFIDENCE' ORDER BY id LIMIT 500")
    List<ActiveLearningSampleEntity> selectLowConfidenceAll();
}
