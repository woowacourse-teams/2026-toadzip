package com.toadzip.backend.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.search.domain.LocationSearchType;
import com.toadzip.backend.search.dto.request.LocationSearchRequest;
import com.toadzip.backend.search.exception.InvalidSearchRequestException;
import com.toadzip.backend.search.repository.KakaoLocationSearchRepository;
import com.toadzip.backend.search.repository.LocationSearchPage;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocationSearchServiceTest {

    private final KakaoLocationSearchRepository repository = mock(KakaoLocationSearchRepository.class);
    private final LocationSearchService service = new LocationSearchService(repository);

    @Test
    void 검색어를_정규화하고_기본_페이지와_크기를_적용한다() {
        when(repository.search("서울 역", LocationSearchType.PLACE, 0, 5))
                .thenReturn(new LocationSearchPage(List.of(), false, 0L));

        var response = service.search(new LocationSearchRequest(" 서울   역 ", LocationSearchType.PLACE, null, null));

        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(5);
        assertThat(response.totalCount()).isZero();
    }

    @Test
    void 잘못된_검색어_유형_페이지_크기는_외부_요청_전에_거부한다() {
        List<LocationSearchRequest> invalidRequests = List.of(
                new LocationSearchRequest("서", LocationSearchType.PLACE, 0, 5),
                new LocationSearchRequest("가".repeat(51), LocationSearchType.PLACE, 0, 5),
                new LocationSearchRequest("서울", null, 0, 5),
                new LocationSearchRequest("서울", LocationSearchType.PLACE, -1, 5),
                new LocationSearchRequest("서울", LocationSearchType.PLACE, 101, 5),
                new LocationSearchRequest("서울", LocationSearchType.PLACE, 0, 0),
                new LocationSearchRequest("서울", LocationSearchType.PLACE, 0, 16)
        );
        invalidRequests.forEach(request -> assertThatThrownBy(() -> service.search(request))
                .isInstanceOf(InvalidSearchRequestException.class));
        verifyNoInteractions(repository);
    }
}
