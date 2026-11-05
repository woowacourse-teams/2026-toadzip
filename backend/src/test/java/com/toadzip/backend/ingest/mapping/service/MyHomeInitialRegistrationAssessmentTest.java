package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.fixture.MyHomeSourceViews;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.location.domain.GeocodedRoadAddress;
import com.toadzip.backend.ingest.location.domain.RoadAddressGeocodingFailureReason;
import com.toadzip.backend.ingest.location.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.location.service.RoadAddressGeocodingService;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationProductStore.Link;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationProductStore.Product;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MyHomeInitialRegistrationAssessmentTest {

    private static final String ADDRESS = "대전광역시 서구 둔산로 100";
    private static final String PNU = "3017011300102180007";
    private static final String IDENTIFIER = "123:PERMANENT_RENTAL";

    @Mock
    private RoadAddressGeocodingService geocoding;
    private MyHomeInitialRegistrationAssessment assessment;

    @BeforeEach
    void setUp() {
        assessment = new MyHomeInitialRegistrationAssessment(new MyHomeComplexSourceMapper(), geocoding);
    }

    @Test
    void 원천만_있고_좌표가_없으면_완료로_표시하지_않는다() {
        when(geocoding.geocode(ADDRESS)).thenThrow(new RoadAddressGeocodingException(
                RoadAddressGeocodingFailureReason.ADDRESS_NOT_FOUND, "주소 일치 없음"));
        var result = assessment.assess(IDENTIFIER, List.of(source(ADDRESS, "26A")), index());
        assertThat(result.status()).isEqualTo(InitialRegistrationStatus.LOCATION_REQUIRED);
        assertThat(result.complexId()).isNull();
    }

    @Test
    void 좌표와_필수_정보가_모두_준비된_신규_단지만_등록_가능하다() {
        when(geocoding.geocode(ADDRESS)).thenReturn(new GeocodedRoadAddress(ADDRESS,
                new BigDecimal("36.35"), new BigDecimal("127.38")));
        assertThat(assessment.assess(IDENTIFIER, List.of(source(ADDRESS, "26A")), index()).status())
                .isEqualTo(InitialRegistrationStatus.READY);
    }

    @Test
    void 주택형명이_없으면_정보_보완_필요로_남긴다() {
        var result = assessment.assess(IDENTIFIER, List.of(source(ADDRESS, null)), index());
        assertThat(result.status()).isEqualTo(InitialRegistrationStatus.INFORMATION_REQUIRED);
        assertThat(result.detail()).contains("주택형명");
        verifyNoInteractions(geocoding);
    }

    @Test
    void 주소가_없는_원천도_좌표_준비_목록에_포함한다() {
        var result = assessment.assess(IDENTIFIER, List.of(source(null, "26A")), index());
        assertThat(result.status()).isEqualTo(InitialRegistrationStatus.LOCATION_REQUIRED);
        assertThat(result.detail()).contains("주소");
    }

    @Test
    void 기존_식별자와_원천_연결을_중복_후보보다_먼저_확인한다() {
        Product existing = product(1, "원천과 다른 이름", "10:PERMANENT_RENTAL", false);
        var index = new InitialRegistrationProductIndex(List.of(existing), List.of(new Link(IDENTIFIER, 1)));
        var result = assessment.assess(IDENTIFIER, List.of(source(null, null)), index);
        assertThat(result.status()).isEqualTo(InitialRegistrationStatus.EXISTING);
        assertThat(result.complexId()).isEqualTo(1);
        verifyNoInteractions(geocoding);
    }

    @Test
    void 원천_연결이_없어도_마이홈_식별자가_같으면_기존_단지다() {
        var index = new InitialRegistrationProductIndex(
                List.of(product(1, "기존 단지", IDENTIFIER, false)), List.of());
        assertThat(assessment.assess(IDENTIFIER, List.of(source(ADDRESS, null)), index).status())
                .isEqualTo(InitialRegistrationStatus.EXISTING);
    }

    @Test
    void PDF로_먼저_등록한_동일_단지는_필수_정보가_부족해도_신규_대상에서_제외한다() {
        var result = assessment.assess(IDENTIFIER, List.of(source(ADDRESS, null)),
                new InitialRegistrationProductIndex(List.of(product(1, "대전둔산3", "ADMIN_ENTRY-HOUSING-COMPLEX-1", false)),
                        List.of()));
        assertThat(result.status()).isEqualTo(InitialRegistrationStatus.EXISTING);
        assertThat(result.complexId()).isEqualTo(1);
    }

    @Test
    void PNU만_같거나_여러_후보가_있으면_관리자_확인이_필요하다() {
        var index = new InitialRegistrationProductIndex(List.of(
                product(1, "다른 단지명", "ADMIN_ENTRY-HOUSING-COMPLEX-1", false),
                product(2, "대전둔산3", "ADMIN_ENTRY-HOUSING-COMPLEX-2", false)), List.of());
        var result = assessment.assess(IDENTIFIER, List.of(source(ADDRESS, "26A")), index);
        assertThat(result.status()).isEqualTo(InitialRegistrationStatus.INFORMATION_REQUIRED);
        assertThat(result.detail()).contains("관리자 확인", "1, 2");
        verifyNoInteractions(geocoding);
    }

    @Test
    void 휴지통의_기존_단지는_중복_생성하지_않고_복구_확인을_안내한다() {
        var index = new InitialRegistrationProductIndex(
                List.of(product(1, "대전둔산3", IDENTIFIER, true)), List.of());
        var result = assessment.assess(IDENTIFIER, List.of(source(ADDRESS, "26A")), index);
        assertThat(result.status()).isEqualTo(InitialRegistrationStatus.INFORMATION_REQUIRED);
        assertThat(result.detail()).contains("휴지통");
    }

    private InitialRegistrationProductIndex index() {
        return new InitialRegistrationProductIndex(List.of(), List.of());
    }

    private Product product(long id, String name, String identifier, boolean deleted) {
        return new Product(id, identifier, name, ADDRESS, PNU, "PERMANENT_RENTAL", "LH", "30170", deleted);
    }

    private MyHomeComplexSourceView source(String address, String style) {
        return MyHomeSourceViews.complex(new MyHomeComplexSourceSnapshot(
                123L, "LH대전충남", "30", "대전광역시", "170", "서구", "대전둔산3", address,
                PNU, "19930607", 100, "영구임대", style, new BigDecimal("26"), new BigDecimal("12"),
                "아파트", "개별난방", "복도식", "전체동 설치", 100, 6_361_000L, 87_890L, null));
    }
}
