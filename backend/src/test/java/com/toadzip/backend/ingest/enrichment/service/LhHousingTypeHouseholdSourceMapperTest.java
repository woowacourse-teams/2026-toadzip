package com.toadzip.backend.ingest.enrichment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhLeaseCatalogSourceView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LhHousingTypeHouseholdSourceMapperTest {

    private final LhHousingTypeHouseholdSourceMapper mapper = new LhHousingTypeHouseholdSourceMapper();

    @Test
    void 시도_표기와_구분_기호만_다른_같은_단지는_한_원천_묶음으로_모은다() {
        var grouped = mapper.group(List.of(
                source(1, "서울특별시", "국민임대", "행복 단지(1)", "26.01"),
                source(2, "서울", "국민 임대", "행복단지1", "36.01")
        ));

        assertThat(grouped.entrySet()).singleElement().satisfies(entry -> {
            assertThat(entry.getKey().areaName()).isEqualTo("서울");
            assertThat(entry.getValue()).hasSize(2);
        });
    }

    private LhLeaseCatalogSourceView source(int order, String area, String supplyType, String complex, String exclusiveArea) {
        return LhLeaseCatalogSourceView.read((long) order, order, Instant.parse("2026-10-01T00:00:00Z"),
                new LhCatalogSourceSnapshot(area, supplyType, complex, "100", exclusiveArea, "10", null, null));
    }
}
