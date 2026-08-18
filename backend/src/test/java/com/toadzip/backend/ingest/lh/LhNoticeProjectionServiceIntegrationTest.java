package com.toadzip.backend.ingest.lh;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.toadzip.backend.ingest.lh.source.LhNoticeDetailSourceRepository;
import com.toadzip.backend.ingest.lh.source.LhNoticeSupplySourceRepository;
import com.toadzip.backend.notice.Notice;
import com.toadzip.backend.notice.NoticeAttachmentRepository;
import com.toadzip.backend.notice.NoticeRepository;
import com.toadzip.backend.notice.NoticeScheduleRepository;
import com.toadzip.backend.notice.NoticeSnapshot;
import com.toadzip.backend.notice.NoticeSupply;
import com.toadzip.backend.notice.NoticeSupplyRepository;
import com.toadzip.backend.notice.ReceptionPlaceRepository;
import com.toadzip.backend.notice.RentTerms;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LhNoticeProjectionServiceIntegrationTest {

	private static final String GURI_ADDRESS = "경기도 구리시 체육관로74번길 67";

	private static final String NAMYANGJU_ADDRESS = "경기도 남양주시 순화궁로 458-58";

	private static final String PAN_ID = "2015122300020536";

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-14T01:00:00Z"),
			ZoneId.of("Asia/Seoul"));

	@Autowired
	private NoticeRepository noticeRepository;

	@Autowired
	private NoticeSupplyRepository supplyRepository;

	@Autowired
	private NoticeScheduleRepository scheduleRepository;

	@Autowired
	private ReceptionPlaceRepository receptionPlaceRepository;

	@Autowired
	private NoticeAttachmentRepository attachmentRepository;

	@Autowired
	private LhNoticeDetailSourceRepository detailSourceRepository;

	@Autowired
	private LhNoticeSupplySourceRepository supplySourceRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private EntityManager entityManager;

	private Notice notice;

	private LhNoticeProjectionService service;

	@BeforeEach
	void setUp() {
		TransactionTemplate committed = new TransactionTemplate(transactionManager);
		committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		committed.executeWithoutResult(status -> {
			attachmentRepository.deleteAll();
			receptionPlaceRepository.deleteAll();
			scheduleRepository.deleteAll();
			supplyRepository.deleteAll();
			noticeRepository.deleteAll();
			detailSourceRepository.deleteAll();
			supplySourceRepository.deleteAll();

			notice = noticeRepository.save(Notice.firstVersion(PAN_ID, null, new NoticeSnapshot("일반공고",
					null, "구리·남양주시 행복주택", detailUrl(), null, null, null, "LH", "아파트", "행복주택", null)));
			supplyRepository.save(NoticeSupply.ofComplex(notice, 0, 1, "구리수택", "4131010500108520000",
				GURI_ADDRESS, 50, 394, new RentTerms(37_224_000L, 1_862_000L, 35_362_000L, 156_000L),
				"https://myhome/1", "https://m.myhome/1"));
			supplyRepository.save(NoticeSupply.ofComplex(notice, 1, 3, "남양주별내 A24BL", "4136011100108220000",
				NAMYANGJU_ADDRESS, 117, 872, new RentTerms(22_896_000L, 1_115_000L, 21_751_000L, 103_000L),
				"https://myhome/3", "https://m.myhome/3"));
		});

		service = new LhNoticeProjectionService(noticeRepository, supplyRepository,
				new LhSupplyInfoTypeResolver(), transactionManager, FIXED_CLOCK,
				detailSourceRepository,
				supplySourceRepository, scheduleRepository, receptionPlaceRepository, attachmentRepository);
	}

	@Test
	@DisplayName("LH 원천을 투영하면 공급행을 주택형으로 나누고 단지·일정·접수처·첨부를 보존한다")
	void projectsLhSourcesIntoNoticeSupplyAndChildren() throws Exception {
		LhNoticeSourceNormalizer.Rows rows = new LhNoticeSourceNormalizer(MAPPER).normalize(PAN_ID,
				MAPPER.readTree(detailResponse()), MAPPER.readTree(supplyResponse()));

		service.applyFromSources(notice, rows.details(), rows.supplies());
		entityManager.flush();
		entityManager.clear();

		List<NoticeSupply> supplies = supplyRepository.findByNoticeIdOrderByDisplayOrder(notice.getId());
		assertThat(supplies).hasSize(4);
		assertThat(supplies).filteredOn(supply -> "구리수택 행복주택".equals(supply.getLhComplexLabel()))
				.extracting(NoticeSupply::getTypeName).containsExactly("26", "36");
		assertThat(supplies).filteredOn(supply -> "구리수택 행복주택".equals(supply.getLhComplexLabel()))
				.allSatisfy(supply -> {
					assertThat(supply.getHouseSn()).isEqualTo(1);
					assertThat(supply.getSuppliedPnu()).isEqualTo("4131010500108520000");
					assertThat(supply.getRentTerms().getDeposit()).isEqualTo(37_224_000L);
				});
		assertThat(supplies).filteredOn(supply -> "남양주별내 A24블록".equals(supply.getLhComplexLabel()))
				.singleElement().satisfies(supply -> {
					assertThat(supply.getHouseSn()).isNull();
					assertThat(supply.getRentTerms()).isNull();
				});
		assertThat(supplies).filteredOn(supply -> supply.getTypeName() == null).singleElement()
				.satisfies(supply -> assertThat(supply.getHouseSn()).isEqualTo(3));

		Notice actual = noticeRepository.findById(notice.getId()).orElseThrow();
		assertThat(actual.getCorrectionReason()).isEqualTo("공급호수 정정");
		assertThat(actual.getSchedules()).hasSize(1);
		assertThat(actual.getReceptionPlaces()).hasSize(1);
		assertThat(actual.getAttachments()).extracting(attachment -> attachment.getName())
				.containsExactly("공고문.pdf");
		assertThat(actual.getLhFetchedAt()).isEqualTo(FIXED_CLOCK.instant().atZone(FIXED_CLOCK.getZone()).toLocalDateTime());
	}

	private String detailUrl() {
		return "https://apply.lh.or.kr/apply?panId=" + PAN_ID
				+ "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=10";
	}

	private String detailResponse() {
		return """
				[{"dsEtcInfo":[{"CRC_RSN":"공급호수 정정"}]},
				 {"dsSplScdl":[{"SBD_LGO_NM":"구리수택 행복주택","ACP_DTTM":"2026.08.18 10:00 ~ 08.20 17:00",
				   "PPR_SBM_OPE_ANC_DT":"20260901","PPR_ACP_ST_DT":"20260907","PPR_ACP_CLSG_DT":"20260910",
				   "CTRT_ST_DT":"20260914","CTRT_ED_DT":"20260916"}]},
				 {"dsCtrtPlc":[{"CTRT_PLC_ADR":"경기도 구리시 안내로 1","SIL_OFC_TLNO":"031-000-0000"}]},
				 {"dsSbd":[
				   {"LCC_NT_NM":"구리수택 행복주택","LGDN_ADR":"%s","HSH_CNT":"394","MVIN_XPC_YM":"202711"},
				   {"LCC_NT_NM":"남양주별내 A24블록","LGDN_ADR":"경기도 남양주시 순화궁로 999","HSH_CNT":"872","MVIN_XPC_YM":"202712"}]},
				 {"dsAhflInfo":[{"SL_PAN_AHFL_DS_CD_NM":"공고문","CMN_AHFL_NM":"공고문.pdf",
				   "AHFL_URL":"https://apply.lh.or.kr/files/notice.pdf"}]},
				 {"resHeader":[{"SS_CODE":"Y"}]}]
				""".formatted(GURI_ADDRESS);
	}

	private String supplyResponse() {
		return """
				[{"dsList01":[
				   {"SBD_LGO_NM":"구리수택 행복주택","HTY_NNA":"26","DDO_AR":"26.70","SPL_AR":"36.80",
				    "HSH_CNT":"200","NOW_HSH_CNT":"30","LS_GMY":"공고문 참조","RFE":"공고문 참조"},
				   {"SBD_LGO_NM":"구리수택 행복주택","HTY_NNA":"36","DDO_AR":"36.32","SPL_AR":"49.82",
				    "HSH_CNT":"194","NOW_HSH_CNT":"20","LS_GMY":"공고문 참조","RFE":"공고문 참조"},
				   {"SBD_LGO_NM":"남양주별내 A24블록","HTY_NNA":"36","DDO_AR":"36.32","SPL_AR":"49.82",
				    "HSH_CNT":"872","NOW_HSH_CNT":"117","LS_GMY":"19546000","RFE":"195460"}]},
				 {"resHeader":[{"SS_CODE":"Y"}]}]
				""";
	}
}
