package com.toadzip.backend.housing.controller;

import io.swagger.v3.oas.models.OpenAPI;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

@Component
public class HousingComplexOpenApiCustomizer implements OpenApiCustomizer {

    private static final Set<String> BOUNDS_PARAMETERS = Set.of(
            "southWestLat", "southWestLng", "northEastLat", "northEastLng"
    );

    @Override
    public void customise(OpenAPI openApi) {
        var complexes = openApi.getPaths().get("/api/v1/complexes");
        if (complexes == null || complexes.getGet() == null) {
            return;
        }
        complexes.getGet().getParameters().stream()
                .filter(parameter -> BOUNDS_PARAMETERS.contains(parameter.getName()))
                .forEach(parameter -> parameter.setRequired(false));
    }
}
