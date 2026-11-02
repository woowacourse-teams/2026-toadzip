package com.toadzip.backend.ingest.collection.lh.supply.domain;

import com.toadzip.backend.ingest.exception.exception.EmptyLhSupplyReplacementException;
import com.toadzip.backend.ingest.exception.exception.IncompleteLhSupplyReplacementException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공급행 감소나 빈 응답으로 교체를 보류한 LH 공급 요청을 실패 기록에서 알아보고, 운영자가 승인할 제안 지문을 읽는 규칙이다.
 * 오류 유형과 사유 형식은 이미 저장된 실패 행을 그대로 읽어야 하므로 바꾸지 않는다.
 */
public final class HeldLhSupplyReplacement {

    private static final String INCOMPLETE = IncompleteLhSupplyReplacementException.class.getSimpleName();
    private static final String EMPTY = EmptyLhSupplyReplacementException.class.getSimpleName();
    private static final String FINGERPRINT_LABEL = "proposedFingerprint=";
    private static final Pattern FINGERPRINT = Pattern.compile(FINGERPRINT_LABEL + "([0-9a-f]{64})");

    private HeldLhSupplyReplacement() {
    }

    public static List<String> errorTypes() {
        return List.of(INCOMPLETE, EMPTY);
    }

    public static boolean isHeld(String errorType) {
        return errorTypes().contains(errorType);
    }

    public static String incompleteReason(long missingRowCount, String fingerprint) {
        return "LH 공급 수집 결과에 기존 공급행 " + missingRowCount + "건이 누락되어 원천을 교체하지 않습니다. "
                + FINGERPRINT_LABEL + fingerprint;
    }

    /** 빈 응답의 사유에는 지문이 없지만, 수집 시 빈 공급행 목록의 지문으로 승인 여부를 확인하므로 그 지문을 제안한 것으로 본다. */
    public static Optional<String> proposedFingerprint(String errorType, String reason) {
        if (EMPTY.equals(errorType)) {
            return Optional.of(LhSupplySnapshot.fingerprint(List.of()));
        }
        Matcher matcher = FINGERPRINT.matcher(reason);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    public static boolean proposes(String errorType, String reason, String fingerprint) {
        return proposedFingerprint(errorType, reason).filter(fingerprint::equals).isPresent();
    }
}
