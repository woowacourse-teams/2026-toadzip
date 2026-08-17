package com.toadzip.backend.ingest.myhome;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MyHomeRegionCatalogTest {

	@Test
	@DisplayName("마이홈 전국 시군구 256개를 중복 없이 제공한다")
	void providesNationwideRegionsWithoutDuplicates() {
		var regions = new MyHomeRegionCatalog().all();

		assertThat(regions).hasSize(256);
		assertThat(regions).extracting(MyHomeRegion::fullCode).doesNotHaveDuplicates();
	}

}
