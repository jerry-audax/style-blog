package com.blogsystem.infrastructure.mybatis.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** JDBC arrays stay at the persistence boundary, not in domain signatures. */
@MappedTypes(UUID[].class)
@MappedJdbcTypes(JdbcType.ARRAY)
public final class UuidArrayTypeHandler extends BaseTypeHandler<UUID[]> {
    @Override public void setNonNullParameter(PreparedStatement statement, int index, UUID[] ids, JdbcType jdbcType) throws SQLException {
        Array array = statement.getConnection().createArrayOf("uuid", ids);
        try { statement.setArray(index, array); } finally { array.free(); }
    }

    @Override public UUID[] getNullableResult(ResultSet rows, String name) throws SQLException { return read(rows.getArray(name)); }
    @Override public UUID[] getNullableResult(ResultSet rows, int index) throws SQLException { return read(rows.getArray(index)); }
    @Override public UUID[] getNullableResult(CallableStatement statement, int index) throws SQLException { return read(statement.getArray(index)); }

    private static UUID[] read(Array array) throws SQLException {
        if (array == null) return null;
        try {
            Object[] values = (Object[]) array.getArray();
            UUID[] ids = new UUID[values.length];
            for (int i = 0; i < values.length; i++) {
                ids[i] = values[i] == null ? null : values[i] instanceof UUID id ? id : UUID.fromString(values[i].toString());
            }
            return ids;
        } catch (IllegalArgumentException | ClassCastException failure) {
            throw new SQLException("Invalid stored UUID array");
        } finally { array.free(); }
    }
}
