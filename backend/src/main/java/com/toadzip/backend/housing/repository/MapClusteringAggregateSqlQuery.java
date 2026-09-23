package com.toadzip.backend.housing.repository;

import java.util.Map;

record MapClusteringAggregateSqlQuery(String sql, Map<String, Object> parameters) {

    MapClusteringAggregateSqlQuery {
        parameters = Map.copyOf(parameters);
    }
}
