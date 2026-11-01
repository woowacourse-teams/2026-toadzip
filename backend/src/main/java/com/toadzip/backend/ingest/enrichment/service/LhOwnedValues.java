package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.ingest.domain.LhSourceIdentifier;
import java.util.Collection;

/**
 * LH 보강이 쓴 일정·첨부·공급대상 가운데 보강이 고치거나 지울 수 있는 값이다. LH 원천 식별자를 가졌고
 * 관리자가 고치지 않은 값만 보강의 것이다. 관리자가 고친 LH 값과 LH 원천이 아닌 수기 값은 보강이 손대지 않는다.
 */
final class LhOwnedValues {

    private LhOwnedValues() {
    }

    static boolean owned(String sourceIdentifier, boolean adminModified) {
        return !adminModified && LhSourceIdentifier.isLhSource(sourceIdentifier);
    }

    /** 주어진 LH 공고 가운데 하나에서 온 보강의 값이다. */
    static boolean ownedFrom(String sourceIdentifier, boolean adminModified, Collection<String> panIds) {
        return !adminModified && LhSourceIdentifier.isLhSourceOfAny(sourceIdentifier, panIds);
    }

    /** 관리자가 고친 LH 값이다. 보강은 이 값을 그대로 둔다. */
    static boolean reviewed(String sourceIdentifier, boolean adminModified) {
        return adminModified && LhSourceIdentifier.isLhSource(sourceIdentifier);
    }
}
