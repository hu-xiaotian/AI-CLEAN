package com.aiclean.match;

import com.aiclean.entity.CategoryEntity;
import com.aiclean.entity.CategorySynonymEntity;
import lombok.Data;

import java.util.List;

/**
 * 分类匹配输入上下文。
 * 与具体业务（temp_data / extra_data）解耦，只携带匹配所需的纯数据，
 * 便于将来把匹配算法整体替换为独立服务/远程算法。
 */
@Data
public class CategoryMatchContext {

    /**
     * 从指定列取出的原始分类名称（待匹配文本）
     */
    private String categoryName;

    /**
     * 可选的原始分类编码（如第 1 列），用于编码精确/前缀匹配
     */
    private String categoryCode;

    /**
     * 额外属性值（解析自全描述），作为辅助匹配信号
     */
    private List<String> extraValues;

    /**
     * 全部分类（含各级节点）
     */
    private List<CategoryEntity> allCategories;

    /**
     * 同义词表（别名 -> 标准分类）
     */
    private List<CategorySynonymEntity> synonyms;
}
