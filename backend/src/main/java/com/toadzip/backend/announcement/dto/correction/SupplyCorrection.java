package com.toadzip.backend.announcement.dto.correction;

import com.toadzip.backend.announcement.dto.request.RejectUnknownJsonFields;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 공급정보 편집표와 PDF 보정이 함께 쓰는 공고 공급표 보정 요청·응답. */
public final class SupplyCorrection {
    private SupplyCorrection() { }

    /**
     * 공급 보정 작업실. snapshotToken은 공급표·공고 단지 연결·원천 참고 행을 덮는다. applicationTypeNames는 이 공고 주택형의 신청형 이름(중복 없음),
     * sourceReference는 LH·GH 공급 원천 행으로 저장하지 않고 수량 검산에만 쓴다.
     */
    public record Workspace(String snapshotToken, long version, SupplySheetView sheet,
            List<AnnouncementComplexView> announcementComplexes, List<String> applicationTypeNames,
            List<SourceReference> sourceReference, List<String> warnings) { }
    /** linkMethod는 AUTO·ADMIN·MIGRATED, 연결이 없으면(휴지통 단지 포함) null이다. rowCount는 이 공고 단지의 주택형 수다. */
    public record AnnouncementComplexView(long id, String name, String roadAddress, Long housingComplexId, String housingComplexName,
            String linkMethod, int rowCount) { }
    public record ComplexLinkRequest(@NotBlank @Size(max = 200) String snapshotToken, @Positive long housingComplexId) { }
    /** 원천 공급 행 하나. householdCount는 원천의 이번 공급 수(정수가 아니면 null)다. */
    public record SourceReference(String complexLabel, String typeName, BigDecimal exclusiveArea, Integer householdCount) { }
    /** after는 저장할 공급표다. 저장된 개체는 보기 key, 새 개체는 요청 key(new:*)를 쓴다. */
    public record Preview(String confirmationToken, SupplySheetView before, SupplySheetView after, List<String> warnings) { }
    public record Applied(long changeId, boolean alreadyApplied, Workspace workspace) { }
    public record Request(
            @NotNull UUID operationId,
            @NotBlank @Size(max = 64) String snapshotToken,
            @NotBlank @Size(max = 2000) String reason,
            @NotBlank @Size(max = 2048) @Pattern(regexp = "^https?://[^\\s]+$") String sourceUrl,
            @Size(max = 100) String sourcePage,
            @NotNull @Valid SupplySheetInput sheet) implements RejectUnknownJsonFields { }
    public record Apply(@NotNull @Valid Request correction,
            @NotBlank @Size(max = 64) String confirmationToken) { }
}
