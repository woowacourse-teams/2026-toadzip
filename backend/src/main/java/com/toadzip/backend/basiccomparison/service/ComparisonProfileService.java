package com.toadzip.backend.basiccomparison.service;

import com.toadzip.backend.basiccomparison.domain.ComparisonProfile;
import com.toadzip.backend.basiccomparison.domain.FamilyDetailsState;
import com.toadzip.backend.basiccomparison.domain.ProfileField;
import com.toadzip.backend.basiccomparison.domain.ValueState;
import com.toadzip.backend.basiccomparison.dto.ComparisonRequests.SaveProfile;
import com.toadzip.backend.basiccomparison.dto.ComparisonResponses.Catalog;
import com.toadzip.backend.basiccomparison.dto.ComparisonResponses.InputField;
import com.toadzip.backend.basiccomparison.dto.ComparisonResponses.Profile;
import com.toadzip.backend.basiccomparison.exception.InvalidComparisonException;
import com.toadzip.backend.basiccomparison.repository.ComparisonJsonCodec;
import com.toadzip.backend.basiccomparison.repository.ComparisonProfileRepository;
import com.toadzip.backend.basiccomparison.repository.ComparisonRegionMembership;
import com.toadzip.backend.basiccomparison.repository.EligibilityWorkRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
@PreAuthorize("hasRole('USER')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ComparisonProfileService {
    private final ComparisonProfileRepository profiles;
    private final ComparisonRegionMembership regions;
    private final ComparisonJsonCodec codec;
    private final Clock clock;
    private final EligibilityWorkRepository work;

    public ComparisonProfileService(
            ComparisonProfileRepository profiles,
            ComparisonRegionMembership regions,
            ComparisonJsonCodec codec,
            Clock clock, EligibilityWorkRepository work) {
        this.profiles = profiles;
        this.regions = regions;
        this.codec = codec;
        this.clock = clock;
        this.work = work;
    }

    public Profile find(long userId) {
        return Profile.of(profiles.find(userId), work.profileProgress(userId).state());
    }

    @Transactional
    public Profile save(long userId, SaveProfile request) {
        ComparisonProfile profile = request.profile();
        profile.validate(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
        Object residence = profile.value(ProfileField.RESIDENCE_REGION).value();
        if (residence instanceof String code && !regions.isKnown(code)) {
            throw new InvalidComparisonException("지역 카탈로그에 등록된 거주지역 코드를 입력해 주세요.");
        }
        profiles.lockUser(userId);
        return Profile.of(
                profiles.save(
                        userId, request.expectedVersion(), codec.write(profile), clock.instant()), "PENDING");
    }

    public Catalog catalog() {
        return new Catalog(
                ComparisonProfile.CONTRACT_VERSION,
                Arrays.stream(ProfileField.values()).map(this::field).toList(),
                List.of(ValueState.values()),
                List.of(FamilyDetailsState.values()),
                Map.of(
                        ProfileField.Flow.ALWAYS,
                        "현재 기본 입력. 모르는 값은 UNKNOWN으로 둘 수 있습니다.",
                        ProfileField.Flow.UNMARRIED,
                        "미혼일 때 수집하며 독립은 세대분리 기준입니다.",
                        ProfileField.Flow.WITH_FAMILY,
                        "한부모 또는 부부/예비부부일 때 수집합니다.",
                        ProfileField.Flow.COUPLE,
                        "부부/예비부부일 때 수집합니다. 예정일은 실제 신고일이 아닙니다.",
                        ProfileField.Flow.OPTIONAL_FAMILY,
                        "선택 입력입니다. SKIPPED로 생략해도 다른 비교는 이용할 수 있습니다.",
                        ProfileField.Flow.PARENTS_EXCEPTION,
                        "미혼 대학생이 세대분리했고 본인 월소득이 0임을 입력한 경우에만 수집합니다."),
                "만 19세 이상 개인의 현재 입력을 비교합니다. 법정 세대 범위와 공적자료 산정 방식은 공고에서 확인해 주세요.");
    }

    private InputField field(ProfileField field) {
        return new InputField(
                field,
                field.type(),
                field.label(),
                field.unit(),
                field.flow(),
                field.optional(),
                choices(field),
                note(field));
    }

    private List<String> choices(ProfileField field) {
        return switch (field.type()) {
            case FAMILY_TYPE -> List.of("UNMARRIED", "SINGLE_PARENT", "COUPLE_OR_ENGAGED");
            case CHILD_DATE -> List.of("BORN", "EXPECTED");
            case MARRIAGE_DATE -> List.of("REGISTERED", "PLANNED");
            default -> List.of();
        };
    }

    private String note(ProfileField field) {
        return switch (field) {
            case CURRENT_REGION_RESIDENCE_MONTHS ->
                    "현재 거주지역 코드 범위에서 계속 거주한 완료 개월 수(0~1800)입니다. 현재 집 거주기간과 다릅니다.";
            case CURRENT_LEASE_END_DATE ->
                    "현재 거주 집의 계약 종료일입니다. 미래 날짜도 허용합니다. 종료일이 없는 거주는 NOT_APPLICABLE, 모르면 UNKNOWN입니다.";
            case REGISTERED_FAMILY_COUNT -> "본인을 포함한 등본상 가족 수이며 태아는 제외합니다. 세대주 여부를 뜻하지 않습니다.";
            case HOUSEHOLD_MONTHLY_INCOME -> "세대 합산 세전 월평균소득입니다. 선택 가족정보를 생략해도 유지됩니다.";
            case FAMILY_TOTAL_ASSETS, FAMILY_OWNS_HOUSE, FAMILY_OWNS_CAR ->
                    "본인을 포함한 가족 전체 값입니다. 공고의 법정 세대 범위와 같은지 직접 확인해 주세요.";
            case APPLICANT_CAR_VALUE, FAMILY_CAR_VALUE ->
                    "해당 범위의 차량 중 최고 가액입니다. 자동차 없음이면 NOT_APPLICABLE로 입력하며 예외 차량은 원문을 확인합니다.";
            case FAMILY_TYPE -> "한부모라는 답은 법정 지원대상 한부모가족 자격을 뜻하지 않습니다.";
            case YOUNGEST_CHILD, MARRIAGE_EVENT -> "예정일이 지나도 실제 출생일이나 혼인신고일로 바꾸지 않습니다.";
            default -> "미수집 법정 요건은 이 입력에서 추정하지 않습니다.";
        };
    }
}
