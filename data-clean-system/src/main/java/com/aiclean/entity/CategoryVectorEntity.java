package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 标准分类语义向量实体类（category_vector）。
 * <p>
 * 存储 main_data_category 每个标准分类的 Embedding 语义向量，用于基于余弦相似度召回语义最接近的备选分类。
 * 向量以 JSON 数组字符串形式持久化（{@link #vectorText}），读取时反序列化为 double[]。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("category_vector")
public class CategoryVectorEntity extends BaseEntity {

    /**
     * 关联的标准分类ID（main_data_category.id）
     */
    private Long categoryId;

    /**
     * 关联的标准分类编码（冗余存储，便于排查/联查）
     */
    private String categoryCode;

    /**
     * 向量化所用的原始文本（分类名称+路径+说明+旧名称，便于追溯向量来源）
     */
    private String vectorSource;

    /**
     * Embedding 模型名称（向量来源模型，模型更换后需重新向量化）
     */
    private String embeddingModel;

    /**
     * 向量维度
     */
    private Integer dimension;

    /**
     * 语义向量，JSON 数组字符串，如 "[0.1,0.2,-0.3,...]"
     */
    private String vectorText;
}
