package com.toadzip.backend.ingest.lh.refinement.domain;

import com.toadzip.backend.housing.domain.RentalType;
import java.util.Optional;

/** LH 공고목록의 공고 유형 코드를 제품 공급유형으로 바꾼다. 신혼희망타운 행복주택(42)·통합공공임대(51)는 같은 공급유형으로 본다. */
public final class LhRentalTypes {

    private LhRentalTypes() {
    }

    /** 공공임대(08)는 공급정보 유형으로 50년(`061`)과 분양전환(`060`)을 나눈다. 정할 수 없으면 비어 있다. */
    public static Optional<RentalType> productType(String announcementTypeCode, String supplyInfoTypeCode) {
        if (announcementTypeCode == null) {
            return Optional.empty();
        }
        return switch (announcementTypeCode) {
            case "07" -> Optional.of(RentalType.NATIONAL_RENTAL);
            case "09" -> Optional.of(RentalType.PERMANENT_RENTAL);
            case "10", "42" -> Optional.of(RentalType.HAPPY_HOUSING);
            case "48", "51" -> Optional.of(RentalType.INTEGRATED_PUBLIC_RENTAL);
            case "08" -> publicRentalTerm(supplyInfoTypeCode);
            default -> Optional.empty();
        };
    }

    private static Optional<RentalType> publicRentalTerm(String supplyInfoTypeCode) {
        if ("061".equals(supplyInfoTypeCode)) {
            return Optional.of(RentalType.PUBLIC_RENTAL_50Y);
        }
        if ("060".equals(supplyInfoTypeCode)) {
            return Optional.of(RentalType.PUBLIC_RENTAL);
        }
        return Optional.empty();
    }
}
