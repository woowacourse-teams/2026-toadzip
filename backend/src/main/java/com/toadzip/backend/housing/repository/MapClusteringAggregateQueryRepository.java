package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringGroupKey;
import com.toadzip.backend.housing.domain.MapClusteringRegionAssignment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MapClusteringAggregateQueryRepository {

    private final JdbcClient jdbcClient;
    private final MapClusteringAggregateSqlBuilder sqlBuilder;

    public MapClusteringAggregateQueryRepository(
            JdbcClient jdbcClient,
            MapClusteringAggregateSqlBuilder sqlBuilder
    ) {
        this.jdbcClient = jdbcClient;
        this.sqlBuilder = sqlBuilder;
    }

    public List<MapClusteringRegionCountRow> findCounts(
            HousingComplexFilterCondition filters,
            List<MapClusteringRegionAssignment> assignments
    ) {
        MapClusteringAggregateSqlQuery query = sqlBuilder.build(filters, assignments);
        return jdbcClient.sql(query.sql())
                .params(query.parameters())
                .query(this::mapRow)
                .list();
    }

    private MapClusteringRegionCountRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MapClusteringRegionCountRow(
                new MapClusteringGroupKey(resultSet.getString("group_key")),
                resultSet.getLong("unique_complex_count")
        );
    }
}
