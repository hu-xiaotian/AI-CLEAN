package com.aiclean.mapper;

import com.aiclean.entity.ParseRuleEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 解析规则Mapper
 */
@Mapper
public interface ParseRuleMapper extends BaseMapper<ParseRuleEntity> {

    @Select("SELECT * FROM parse_rule WHERE is_active = 1 ORDER BY created_at DESC")
    List<ParseRuleEntity> selectActiveRules();

    @Select("SELECT * FROM parse_rule WHERE rule_name LIKE CONCAT('%', #{keyword}, '%')")
    List<ParseRuleEntity> selectByName(@Param("keyword") String keyword);
}
