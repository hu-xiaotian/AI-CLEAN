package com.aiclean.handler;

import com.aiclean.entity.enums.DataStatus;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.*;

/**
 * DataStatus 枚举自定义 TypeHandler
 * 达梦 JDBC 驱动不支持 ResultSet.getObject(col, EnumClass)，
 * 因此用 getString 手动读取，避免 MyBatis-Plus 默认枚举处理器报错。
 */
@MappedTypes(DataStatus.class)
@MappedJdbcTypes(JdbcType.VARCHAR)
public class DataStatusTypeHandler extends BaseTypeHandler<DataStatus> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, DataStatus parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter.getCode());
    }

    @Override
    public DataStatus getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String code = rs.getString(columnName);
        return rs.wasNull() ? null : DataStatus.fromCode(code);
    }

    @Override
    public DataStatus getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String code = rs.getString(columnIndex);
        return rs.wasNull() ? null : DataStatus.fromCode(code);
    }

    @Override
    public DataStatus getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String code = cs.getString(columnIndex);
        return cs.wasNull() ? null : DataStatus.fromCode(code);
    }
}
