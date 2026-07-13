package com.aiclean.mapper;

import com.aiclean.entity.ExtraDataTitleEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 补充数据表头Mapper
 */
@Mapper
public interface ExtraDataTitleMapper extends BaseMapper<ExtraDataTitleEntity> {

    @Select("SELECT * FROM extra_data_title WHERE temp_data_title_id = #{titleId} ORDER BY id DESC LIMIT 1")
    ExtraDataTitleEntity selectByTempDataTitleId(@Param("titleId") Long titleId);

    /**
     * 根据原始数据表头ID物理删除所有补充数据表头
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM extra_data_title WHERE temp_data_title_id = #{titleId}")
    int deleteByTempDataTitleId(@Param("titleId") Long titleId);
}
