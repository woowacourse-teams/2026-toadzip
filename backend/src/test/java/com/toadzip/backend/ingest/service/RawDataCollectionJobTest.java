package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RawDataCollectionJobTest {

    @Test
    void 단지_원본_수집_작업은_COMPLEX로_분류한다() {
        assertThat(RawDataCollectionJob.values())
                .filteredOn(job -> job.category() == RawDataCategory.COMPLEX)
                .containsExactly(
                        RawDataCollectionJob.MYHOME_COMPLEX,
                        RawDataCollectionJob.LH_LEASE_CATALOG
                );
    }

    @Test
    void 공고_원본_수집_작업은_NOTICE로_분류한다() {
        assertThat(RawDataCollectionJob.values())
                .filteredOn(job -> job.category() == RawDataCategory.NOTICE)
                .containsExactly(
                        RawDataCollectionJob.MYHOME_NOTICE,
                        RawDataCollectionJob.LH_NOTICE
                );
    }
}
