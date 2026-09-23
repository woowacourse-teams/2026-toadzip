package com.toadzip.backend.housing.repository;

import java.util.Map;

record HousingComplexFilterPredicate(String sql, Map<String, Object> parameters) {

    HousingComplexFilterPredicate {
        parameters = Map.copyOf(parameters);
    }
}
