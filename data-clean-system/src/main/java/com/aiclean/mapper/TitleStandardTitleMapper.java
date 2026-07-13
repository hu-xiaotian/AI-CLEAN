package com.aiclean.mapper;

import com.aiclean.entity.StandardTitleEntity;
import com.aiclean.entity.TitleStandardTitleEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据文件-标准字段表头关联 Mapper
 */
@Mapper
public interface TitleStandardTitleMapper extends BaseMapper<TitleStandardTitleEntity> {

    /**
     * 查询某数据文件关联的所有标准字段表头（已按标准表头ID排序）
     */
    @Select("SELECT st.* FROM standard_title st " +
            "INNER JOIN title_standard_title tst ON tst.standard_title_id = st.id " +
            "WHERE tst.temp_data_title_id = #{tempDataTitleId} ORDER BY st.id")
    List<StandardTitleEntity> selectStandardTitlesByTitleId(@Param("tempDataTitleId") Long tempDataTitleId);

    /**
     * 判断关联是否已存在
     */
    @Select("SELECT 1 FROM title_standard_title " +
            "WHERE temp_data_title_id = #{tempDataTitleId} AND standard_title_id = #{standardTitleId}")
    Integer exists(@Param("tempDataTitleId") Long tempDataTitleId,
                   @Param("standardTitleId") Long standardTitleId);

    /**
     * 删除某数据文件下指定标准表头的关联
     */
    @Delete("DELETE FROM title_standard_title " +
            "WHERE temp_data_title_id = #{tempDataTitleId} AND standard_title_id = #{standardTitleId}")
    int deleteByTitleAndStandard(@Param("tempDataTitleId") Long tempDataTitleId,
                                 @Param("standardTitleId") Long standardTitleId);
}
