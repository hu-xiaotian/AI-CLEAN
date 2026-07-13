package com.aiclean.mapper;

import com.aiclean.entity.StandardTitleEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 标准字段表头Mapper
 */
@Mapper
public interface StandardTitleMapper extends BaseMapper<StandardTitleEntity> {

    @Select("SELECT * FROM standard_title WHERE category_code = #{categoryCode} ORDER BY id ASC LIMIT 1")
    StandardTitleEntity selectByCategoryCode(@Param("categoryCode") String categoryCode);
}
