package com.toadzip.backend.ingest.myhome.source;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.myhome.MyHomeComplexSourceItem;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyHomeComplexSourceStoreBatchTest {

	@Mock
	private MyHomeComplexSourceRepository repository;

	@Test
	@DisplayName("원천 자연키를 한 번에 조회하고 신규 행을 일괄 저장한다")
	void loadsExistingRowsAndSavesNewRowsInBatch() {
		when(repository.findAllBySourceKeyIn(any())).thenReturn(List.of());
		MyHomeComplexSourceStore store = new MyHomeComplexSourceStore(repository);

		store.store(List.of(item("46A"), item("59A")));

		verify(repository).findAllBySourceKeyIn(argThat(keys -> keys.size() == 2));
		verify(repository).saveAll(any());
	}

	private MyHomeComplexSourceItem item(String styleName) {
		return new MyHomeComplexSourceItem(123L, "LH", "11", "서울특별시", "110", "종로구", "테스트 단지",
				"서울특별시 종로구 테스트로 1", "1111010100100010000", "20200101", 100, "국민임대", styleName,
				new BigDecimal("46.8"), new BigDecimal("20.2"), "아파트", "지역난방", "복도식", "전체동 설치", 80,
				10_000_000L, 200_000L, 20_000_000L);
	}

}
