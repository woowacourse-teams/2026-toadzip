package com.toadzip.backend.ingest.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MyHomeRegionCatalogTest {

    @Test
    @DisplayName("마이홈 전국 지역 코드를 읽는다")
    void loadsNationwideRegionCodes() {
        MyHomeRegionCatalog catalog = new MyHomeRegionCatalog();

        assertThat(catalog.findAll()).hasSizeGreaterThan(200);
        assertThat(catalog.find("11", "110").description()).isEqualTo("서울특별시 종로구");
    }
}
