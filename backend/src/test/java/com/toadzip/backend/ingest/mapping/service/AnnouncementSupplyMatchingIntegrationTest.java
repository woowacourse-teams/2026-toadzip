package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.fixture.MyHomeSourceViews;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementSourceFixtures;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnnouncementSupplyMatchingIntegrationTest {
    private static final String TARGET = "manual-21395";
    private static final String PNU = "2823710500105500000";
    @Autowired private AnnouncementSupplyRowQueryService rowQuery;
    @Autowired private AnnouncementSupplyCandidateService candidateService;
    @Autowired private MyHomeAnnouncementSourceFixtures sources;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private SupplyRowRepository supplies;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private JdbcClient jdbc;
    @Autowired private SupplyCorrectionSources correctionSources;
    private HousingComplex complex;
    private HousingType type;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE housing_complexes, announcements, myhome_announcement_source_bundles,
                    myhome_announcement_mapping_failures, announcement_supply_match_decisions,
                    admin_data_changes, lh_announcement_query_sources CASCADE
                """).update();
        complex = complex("한스빌아파트", "complex-21395", PNU, "NATIONAL_RENTAL");
        type = types.save(HousingType.createFromMyHome(complex, "complex-21395:26", "26",
                new BigDecimal("26.0000"), new BigDecimal("40.0000")));
        sources.save(source(TARGET, 1, PNU));
    }

    @Test
    void 단지_검색과_주택형_조회는_해당_단지의_기존_데이터만_반환한다() {
        assertThat(candidateService.complexes("한스빌")).singleElement()
                .satisfies(value -> {
                    assertThat(value.id()).isEqualTo(complex.getId());
                    assertThat(value.pnu()).isEqualTo(complex.getAddress().getPnu());
                    assertThat(value.totalHouseholdCount()).isEqualTo(complex.getTotalHouseholdCount());
                    assertThat(value.housingTypes()).singleElement().satisfies(area -> {
                        assertThat(area.id()).isEqualTo(type.getId());
                        assertThat(area.exclusiveArea()).isEqualByComparingTo(type.getExclusiveArea());
                    });
                });
        assertThat(candidateService.housingTypes(complex.getId())).singleElement()
                .satisfies(value -> assertThat(value.id()).isEqualTo(type.getId()));
    }

    @Test
    void 주택형_조회는_PDF_보정이_정한_기본_임대조건의_출처를_함께_준다() {
        type.observeAnnouncement(new HousingType.AnnouncementValues(null, null, null, 8_100_000L, 156_230L),
                Instant.parse("2026-10-01T00:00:00Z"));
        types.save(type);

        assertThat(candidateService.housingTypes(complex.getId())).singleElement().satisfies(value -> {
            assertThat(value.basicDeposit()).isEqualTo(8_100_000L);
            assertThat(value.rentalConditionSource()).isEqualTo("ANNOUNCEMENT");
        });
    }

    @Test
    void 단지_검색에_원천_PNU를_주면_이름이_다른_같은_필지_단지를_먼저_후보로_준다() {
        var main = complex("중촌주공2단지", "complex-main", PNU, "NATIONAL_RENTAL");
        complex("한스빌 다른필지", "complex-other", "1111111111111111111", "NATIONAL_RENTAL");

        assertThat(candidateService.complexes("한스빌", PNU)).extracting(value -> value.id())
                .containsExactly(complex.getId(), main.getId(), complexes.findBySourceComplexIdentifier("complex-other")
                        .orElseThrow().getId());
        assertThat(candidateService.complexes("한스빌")).extracting(value -> value.id()).doesNotContain(main.getId());
        assertThatThrownBy(() -> candidateService.complexes("한스빌", "12a")).hasMessageContaining("PNU");
    }

    /** 지원을 종료한 마이홈 공고 정제가 남긴 공고처럼, 지금 원천의 해석 결과대로 공고와 공급행을 등록한다. */
    private void registerFromSources() {
        var first = sources.findAll().getFirst();
        var announcement = announcements.save(Announcement.create(TARGET, null, null, first.getPblancNm(),
                AnnouncementPublicationType.ORIGINAL, RentalType.NATIONAL_RENTAL, RecruitmentType.WAITLIST,
                AgencyCode.LH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 11, 6), first.getUrl(), null, 0L, null));
        int order = 0;
        for (var data : correctionSources.supplyRows(TARGET)) {
            var housingType = data.exclusiveArea() == null ? null : types.findAll().stream()
                    .filter(value -> value.getExclusiveArea().compareTo(data.exclusiveArea()) == 0)
                    .findFirst().orElse(null);
            var row = SupplyRow.create(announcement, complex, housingType, data.sourceSupplyRowIdentifier(), order++,
                    data.sourceComplexName(), data.sourceHousingTypeName(), data.pnu(), null, data.supplyCategory(),
                    null, data.totalSupplyHouseholdCount());
            row.recordMyHomeSource(data.source().getSourceKey());
            supplies.save(row);
        }
    }

    private HousingComplex complex(String name, String identifier, String pnu, String supplyType) {
        return complexes.save(HousingComplex.createFromMyHome(name, identifier, supplyType,
                Address.create("익산시 목천로 30-14", pnu, pnu.substring(0, 10), "28", "28237",
                        new BigDecimal("35.9"), new BigDecimal("126.9")),
                100, "SH", null, "INDIVIDUAL", "APARTMENT", "CORRIDOR", true, 80));
    }

    private MyHomeAnnouncementSourceView source(String identifier, int house, String pnu) {
        var source = MyHomeSourceViews.announcement(house, new MyHomeAnnouncementSourceSnapshot(identifier,
                house, "모집중", "국민임대 입주자 모집공고", "서울주택도시공사", "26A,B", "국민임대", null,
                "20261001", "20261106", "20261008", "20261015", null, "https://example.com/manual", null,
                null, "익산한스빌", "전북특별자치도", "익산시", null, null, null, pnu, null, null,
                0, null, null, null, null));
        source = MyHomeSourceViews.collectedAt(source, Instant.parse("2026-10-08T00:00:00Z"));
        return source;
    }
}
