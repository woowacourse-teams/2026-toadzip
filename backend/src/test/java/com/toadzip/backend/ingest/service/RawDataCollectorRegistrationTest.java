package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RawDataCollectorRegistrationTest {

    @Autowired
    private List<RawDataCollector<?>> collectors;

    @Test
    void 현재_원본_수집_작업을_중복_없이_등록한다() {
        assertThat(collectors)
                .extracting(RawDataCollector::job)
                .containsExactlyInAnyOrder(
                        RawDataCollectionJob.MYHOME_COMPLEX,
                        RawDataCollectionJob.LH_LEASE_CATALOG,
                        RawDataCollectionJob.MYHOME_NOTICE,
                        RawDataCollectionJob.LH_NOTICE
                );
    }
}
