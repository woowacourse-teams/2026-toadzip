package com.toadzip.backend.announcement.domain;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 추출 프롬프트가 읽어야 하는 공고문 영역. 본문 문장에도 나오는 낱말이 많아서
 * 표·절 제목처럼 값(면적·금액·날짜·전화번호)이 함께 있는 쪽만 인정한다.
 */
enum ExtractionSection {
    SUPPLY(true) {
        @Override
        boolean matches(ExtractionPageText page) {
            return page.contains(COMMON_AREA) && page.count(AREA) >= 3;
        }
    },
    RENT(true) {
        @Override
        boolean matches(ExtractionPageText page) {
            return page.contains(DEPOSIT) && page.contains(RENT_FEE) && page.count(MONEY) >= 2;
        }
    },
    SCHEDULE(false) {
        @Override
        boolean matches(ExtractionPageText page) {
            return page.contains(SCHEDULE_HEADING) && page.count(DATE) >= 1;
        }
    },
    CONTACT(false) {
        @Override
        boolean matches(ExtractionPageText page) {
            return page.contains(CONTACT_HEADING) && page.count(PHONE) >= 1;
        }
    },
    TARGET(false) {
        @Override
        boolean matches(ExtractionPageText page) {
            return page.contains(TARGET_HEADING);
        }
    };

    private static final Pattern COMMON_AREA = Pattern.compile("공용");
    private static final Pattern DEPOSIT = Pattern.compile("임대보증금");
    private static final Pattern RENT_FEE = Pattern.compile("임대료");
    private static final Pattern SCHEDULE_HEADING = Pattern.compile("공급일정|신청일정|신청기간|접수기간|신청일시");
    private static final Pattern CONTACT_HEADING = Pattern.compile("문의처|신청문의|임대문의|접수장소|접수처");
    private static final Pattern TARGET_HEADING = Pattern.compile("[가나]군\\(|「[가나]」군|입주자선정방법|선정순위");
    private static final Pattern AREA = Pattern.compile("\\d{2,3}\\.\\d{2,4}");
    // 천원 단위 표(67,400)도 있어 콤마가 하나 이상인 금액을 센다. 임대보증금·임대료 낱말이 함께 있어야 한다.
    private static final Pattern MONEY = Pattern.compile("\\d{1,3}(?:,\\d{3})+");
    private static final Pattern DATE = Pattern.compile("\\d{2}\\.\\d{1,2}\\.\\d{1,2}");
    private static final Pattern PHONE = Pattern.compile("\\d{2,4}-\\d{3,4}-\\d{4}|1\\d{3}-\\d{4}");

    private final boolean required;

    ExtractionSection(boolean required) {
        this.required = required;
    }

    abstract boolean matches(ExtractionPageText page);

    /** 공급표와 임대조건표는 다음 쪽으로 이어질 수 있고, 둘 중 하나라도 없으면 쪽 선별을 믿지 않는다. */
    boolean continuesToNextPage() {
        return required;
    }

    static List<ExtractionSection> required() {
        return Arrays.stream(values()).filter(section -> section.required).toList();
    }
}
