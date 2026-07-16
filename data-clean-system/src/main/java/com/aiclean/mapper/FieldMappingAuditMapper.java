package com.aiclean.mapper;

import com.aiclean.entity.FieldMappingAuditEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 字段映射审核Mapper
 */
@Mapper
public interface FieldMappingAuditMapper extends BaseMapper<FieldMappingAuditEntity> {

    @Select("SELECT * FROM field_mapping_audit WHERE temp_data_title_id = #{titleId} ORDER BY source_type, id")
    List<FieldMappingAuditEntity> selectByTitleId(@Param("titleId") Long titleId);

    @Select("SELECT * FROM field_mapping_audit WHERE temp_data_title_id = #{titleId} AND status = #{status}")
    List<FieldMappingAuditEntity> selectByTitleIdAndStatus(@Param("titleId") Long titleId, @Param("status") String status);

    @Select("SELECT * FROM field_mapping_audit WHERE temp_data_title_id = #{titleId} AND source_type = #{sourceType}")
    List<FieldMappingAuditEntity> selectByTitleIdAndSourceType(@Param("titleId") Long titleId, @Param("sourceType") String sourceType);

    /**
     * 根据原始数据表头ID物理删除所有字段映射
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM field_mapping_audit WHERE temp_data_title_id = #{titleId}")
    int deleteByTitleId(@Param("titleId") Long titleId);

    /**
     * 根据标准字段表头ID + 原始数据表头ID查询字段映射
     */
    @Select("SELECT * FROM field_mapping_audit WHERE standard_title_id = #{standardTitleId} AND temp_data_title_id = #{titleId} ORDER BY source_type, id")
    List<FieldMappingAuditEntity> selectByStandardAndTitle(@Param("standardTitleId") Long standardTitleId, @Param("titleId") Long titleId);

    /**
     * 获取某数据文件下「存在字段映射」的去重标准字段表头ID集合。
     * 用于 fill-all 时只遍历与本文件相关的标准表头，而非系统全部标准表头。
     */
    @Select("SELECT DISTINCT standard_title_id FROM field_mapping_audit WHERE temp_data_title_id = #{titleId}")
    List<Long> selectDistinctStandardTitleIds(@Param("titleId") Long titleId);

    /**
     * 根据标准字段表头ID + 原始数据表头ID物理删除字段映射
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM field_mapping_audit WHERE standard_title_id = #{standardTitleId} AND temp_data_title_id = #{titleId}")
    int deleteByStandardAndTitle(@Param("standardTitleId") Long standardTitleId, @Param("titleId") Long titleId);

    /**
     * 根据标准字段表头ID查询字段映射
     */
    @Select("SELECT * FROM field_mapping_audit WHERE standard_title_id = #{standardTitleId} AND status = #{status}")
    List<FieldMappingAuditEntity> selectByStandardIdAndStatus(@Param("standardTitleId") Long standardTitleId, @Param("status") String status);
}
