package com.toadzip.backend.ingest.lh;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.json.JsonMapper;
import com.toadzip.backend.ingest.lh.source.LhNoticeDetailSource;
import com.toadzip.backend.ingest.lh.source.LhNoticeSupplySource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LhNoticeSourceNormalizerTest {

	@Test
	@DisplayName("LH 상세 데이터셋과 공급 dsList01을 typed source로 보존한다")
	void normalizesAllTypedRows() {
		JsonMapper mapper = JsonMapper.builder().build();
		LhNoticeSourceNormalizer normalizer = new LhNoticeSourceNormalizer(mapper);
		var details = mapper.createArrayNode();
		details.add(mapper.createObjectNode().set("dsEtcInfo", mapper.createArrayNode()
				.add(mapper.createObjectNode().put("CRC_RSN", "정정"))));
		details.add(mapper.createObjectNode().set("dsSbd", mapper.createArrayNode()
				.add(mapper.createObjectNode().put("LCC_NT_NM", "단지").put("LGDN_ADR", "주소"))));
		details.add(mapper.createObjectNode().set("dsSplScdl", mapper.createArrayNode()
				.add(mapper.createObjectNode().put("ACP_DTTM", "20260801"))));
		details.add(mapper.createObjectNode().set("dsCtrtPlc", mapper.createArrayNode()
				.add(mapper.createObjectNode().put("CTRT_PLC_ADR", "접수처"))));
		details.add(mapper.createObjectNode().set("dsAhflInfo", mapper.createArrayNode()
				.add(mapper.createObjectNode().put("SL_PAN_AHFL_DS_CD_NM", "공고문")
						.put("CMN_AHFL_NM", "a.pdf").put("AHFL_URL", "https://example.com/a"))));
		details.add(mapper.createObjectNode().set("dsSbdAhfl", mapper.createArrayNode()
				.add(mapper.createObjectNode().put("LS_SPL_INF_UPL_FL_DS_CD_NM", "이미지")
						.put("CMN_AHFL_NM", "b.png").put("AHFL_URL", "https://example.com/b"))));
		var supply = mapper.createArrayNode();
		supply.add(mapper.createObjectNode().set("dsList01", mapper.createArrayNode()
				.add(mapper.createObjectNode().put("SBD_LGO_NM", "단지").put("HTY_NNA", "46"))));

		LhNoticeSourceNormalizer.Rows rows = normalizer.normalize("P1", details, supply);

		assertThat(rows.details()).extracting(LhNoticeDetailSource::getDatasetType)
				.containsExactly("ETC_INFO", "COMPLEX", "SCHEDULE", "RECEPTION", "NOTICE_FILE", "COMPLEX_IMAGE");
		assertThat(rows.supplies()).extracting(LhNoticeSupplySource::getComplexLabel).containsExactly("단지");
	}
}
