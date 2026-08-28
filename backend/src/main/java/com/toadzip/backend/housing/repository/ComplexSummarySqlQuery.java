package com.toadzip.backend.housing.repository;

import java.util.Map;

record ComplexSummarySqlQuery(String sql, Map<String, Object> parameters) {

    ComplexSummarySqlQuery {
        parameters = Map.copyOf(parameters);
    }
}
