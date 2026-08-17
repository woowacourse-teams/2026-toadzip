package com.toadzip.backend.ingest.lh;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.toadzip.backend.ingest.openapi.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.lh.source.LhNoticeDetailSource;
import com.toadzip.backend.ingest.lh.source.LhNoticeSupplySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class LhNoticeSourceNormalizer {

	private final ObjectMapper objectMapper;

	public LhNoticeSourceNormalizer(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public Rows normalize(String panId, JsonNode detailRoot, JsonNode supplyRoot) {
		List<LhNoticeDetailSource> details = new ArrayList<>();
		int order = 0;
		for (JsonNode row : DataGoKrOpenApiClient.findRows(detailRoot, "dsEtcInfo")) {
			LhNoticeDetail.EtcInfo item = objectMapper.convertValue(row, LhNoticeDetail.EtcInfo.class);
			details.add(detail(order++, panId, "ETC_INFO", null, null, null, null, null, null, null, null, null,
					null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
					item.correctionReason(), item.etcContents()));
		}
		for (JsonNode row : DataGoKrOpenApiClient.findRows(detailRoot, "dsSbd")) {
			LhNoticeDetail.ComplexDetail item = objectMapper.convertValue(row, LhNoticeDetail.ComplexDetail.class);
			details.add(detail(order++, panId, "COMPLEX", item.complexName(), item.lotAddress(), item.lotDetailAddress(),
				item.totalUnitCount(), item.heatingDescription(), item.exclusiveAreaRange(), item.expectedMoveInYearMonth(),
				item.guidanceText(), null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null));
		}
		for (JsonNode row : DataGoKrOpenApiClient.findRows(detailRoot, "dsSplScdl")) {
			LhNoticeDetail.Schedule item = objectMapper.convertValue(row, LhNoticeDetail.Schedule.class);
			details.add(detail(order++, panId, "SCHEDULE", item.complexName(), null, null, null, null, null, null, null,
					item.applicationPeriod(), item.documentTargetAnnouncementDate(), item.documentSubmissionBeginDate(),
					item.documentSubmissionEndDate(), item.contractBeginDate(), item.contractEndDate(), null, null, null, null,
					null, null, null, null, null, null, null, null));
		}
		for (JsonNode row : DataGoKrOpenApiClient.findRows(detailRoot, "dsCtrtPlc")) {
			LhNoticeDetail.Reception item = objectMapper.convertValue(row, LhNoticeDetail.Reception.class);
			details.add(detail(order++, panId, "RECEPTION", null, null, null, null, null, null, null, null, null, null,
					null, null, null, null, item.address(), item.detailAddress(), item.operationBegin(), item.operationEnd(),
					item.phone(), item.guidance(), null, null, null, null, null, null));
		}
		for (JsonNode row : DataGoKrOpenApiClient.findRows(detailRoot, "dsAhflInfo")) {
			LhNoticeDetail.NoticeFile item = objectMapper.convertValue(row, LhNoticeDetail.NoticeFile.class);
			details.add(detail(order++, panId, "NOTICE_FILE", null, null, null, null, null, null, null, null, null, null,
					null, null, null, null, null, null, null, null, null, null, item.kind(), item.name(), item.url(), null,
					null, null));
		}
		for (JsonNode row : DataGoKrOpenApiClient.findRows(detailRoot, "dsSbdAhfl")) {
			LhNoticeDetail.ComplexImage item = objectMapper.convertValue(row, LhNoticeDetail.ComplexImage.class);
			details.add(detail(order++, panId, "COMPLEX_IMAGE", null, null, null, null, null, null, null, null, null,
					null, null, null, null, null, null, null, null, null, null, null, item.kind(), item.name(), item.url(),
					item.complexName(), null, null));
		}

		List<LhNoticeSupplySource> supplies = new ArrayList<>();
		int supplyOrder = 0;
		for (JsonNode row : DataGoKrOpenApiClient.findRows(supplyRoot, "dsList01")) {
			LhUnitSupplyItem item = objectMapper.convertValue(row, LhUnitSupplyItem.class);
			supplies.add(new LhNoticeSupplySource(supplyOrder++, panId, item.complexLabel(), item.typeName(),
					item.exclusiveArea(), item.supplyArea(), item.totalUnitCount(), item.suppliedUnitCount(), item.deposit(),
					item.monthlyRent()));
		}
		return new Rows(details, supplies);
	}

	private LhNoticeDetailSource detail(int order, String panId, String type, String complexName, String address,
			String detailAddress, String totalUnitCount, String heatingDescription, String exclusiveAreaRange,
			String expectedMoveInYearMonth, String guidanceText, String applicationPeriod,
			String documentTargetAnnouncementDate, String documentSubmissionBeginDate,
			String documentSubmissionEndDate, String contractBeginDate, String contractEndDate, String receptionAddress,
			String receptionDetailAddress, String operationBegin, String operationEnd, String phone,
			String receptionGuidance, String kind, String name, String url, String attachmentComplexName,
			String correctionReason, String etcContents) {
		return new LhNoticeDetailSource(order, panId, type, complexName, address, detailAddress, totalUnitCount,
				heatingDescription, exclusiveAreaRange, expectedMoveInYearMonth, guidanceText, applicationPeriod,
				documentTargetAnnouncementDate, documentSubmissionBeginDate, documentSubmissionEndDate, contractBeginDate,
				contractEndDate, receptionAddress, receptionDetailAddress, operationBegin, operationEnd, phone,
				receptionGuidance, kind, name, url, attachmentComplexName, correctionReason, etcContents);
	}

	public record Rows(List<LhNoticeDetailSource> details, List<LhNoticeSupplySource> supplies) {
	}
}
