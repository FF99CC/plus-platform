package org.dromara.project.infrastructure;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.project.domain.Project;

/** Persists the submitted project using the same JSON contract as the HTTP API. */
public class ProjectSnapshotTypeHandler extends BaseTypeHandler<Project> {
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, Project value, JdbcType jdbcType)
            throws SQLException {
        statement.setString(index, JsonUtils.toJsonString(value));
    }

    @Override
    public Project getNullableResult(ResultSet result, String column) throws SQLException {
        return parse(result.getString(column));
    }

    @Override
    public Project getNullableResult(ResultSet result, int column) throws SQLException {
        return parse(result.getString(column));
    }

    @Override
    public Project getNullableResult(CallableStatement statement, int column) throws SQLException {
        return parse(statement.getString(column));
    }

    private Project parse(String value) {
        return value == null ? null : JsonUtils.parseObject(value, Project.class);
    }
}
