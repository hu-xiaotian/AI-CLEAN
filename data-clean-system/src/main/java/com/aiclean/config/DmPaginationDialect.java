package com.aiclean.config;

import com.baomidou.mybatisplus.extension.plugins.pagination.DialectModel;
import com.baomidou.mybatisplus.extension.plugins.pagination.dialects.OracleDialect;

/**
 * 达梦数据库分页方言。
 * <p>
 * 达梦开启 {@code compatibleMode=oracle} 后，MyBatis-Plus 3.5.5 内置的 OracleDialect 生成的分页 SQL
 * 外层派生表缺少别名（{@code SELECT * FROM ( ... ) WHERE ROW_ID > ?}），达梦会报
 * {@code Every derived table must have its own alias}。本类在父类生成的 SQL 基础上为外层派生表补上别名 WRAP。
 * </p>
 */
public class DmPaginationDialect extends OracleDialect {

    private static final String SUFFIX = ") WHERE ROW_ID >";
    private static final String SUFFIX_FIXED = ") WRAP WHERE ROW_ID >";

    @Override
    public DialectModel buildPaginationSql(String originalSql, long offset, long limit) {
        DialectModel model = super.buildPaginationSql(originalSql, offset, limit);
        String sql = model.getDialectSql();
        if (sql.endsWith(SUFFIX)) {
            String fixed = sql.substring(0, sql.length() - SUFFIX.length()) + SUFFIX_FIXED;
            // 重建模型并保持参数顺序：firstParam=limit（ROWNUM <= ?），secondParam=offset（ROW_ID > ?）
            return new DialectModel(fixed, limit, offset).setConsumerChain();
        }
        return model;
    }
}
