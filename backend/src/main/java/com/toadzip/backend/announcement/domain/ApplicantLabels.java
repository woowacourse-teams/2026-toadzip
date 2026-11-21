package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.housing.domain.HousingTypeNames;
import com.toadzip.backend.housing.domain.RentalType;
import java.util.List;

/**
 * 형명 끝의 신청자 표기로 읽는 신청 대상 이름("29(주거약자)" → "주거약자"). 같은 형에 표기가 있는 줄과 함께 온 표기 없는 줄('29'와
 * '29(주거약자)')은 형 이름 대신 신청 대상으로 읽히는 이름을 쓴다. 일반공급이 있는 국민임대·영구임대는 '일반'이고, 그 밖(신혼희망타운
 * 행복주택의 '55'처럼 표기 없는 줄이 일반공급이 아닌 경우)은 '주거약자 외'처럼 함께 온 표기의 나머지다. 신청자격이 '일반'을 일반공급으로
 * 보고 대상 확인 없이 공통 요건으로 판정하므로, 일반공급이 아닌 줄을 '일반'이라 부르지 않는다.
 */
public final class ApplicantLabels {

    public static final String GENERAL = "일반";
    private static final String OTHERS = "그 밖의 대상";

    private ApplicantLabels() {
    }

    /**
     * 이 줄의 신청 대상 이름. siblingNames는 이 줄을 포함해 같은 형으로 함께 온 줄들의 형명이다. 이 줄에 표기가 없고 함께 온 줄도
     * 없거나, (국민임대·영구임대가 아니면서) 함께 온 줄 어디에도 표기가 없으면 이름을 정할 수 없어 빈 문자열이다.
     */
    public static String of(String typeName, List<String> siblingNames, RentalType supplyType) {
        String applicant = HousingTypeNames.applicant(typeName);
        if (!applicant.isEmpty()) {
            return applicant;
        }
        if (siblingNames.size() <= 1) {
            return "";
        }
        if (supplyType == RentalType.NATIONAL_RENTAL || supplyType == RentalType.PERMANENT_RENTAL) {
            return GENERAL;
        }
        // 표기를 '·'로 잇지 않는다. 신청자격이 '·'로 대상 이름을 나눠 '고령자' 경로에 이 줄을 잇게 된다.
        List<String> marked = siblingNames.stream().map(HousingTypeNames::applicant)
                .filter(name -> !name.isEmpty()).distinct().toList();
        return marked.isEmpty() ? "" : marked.size() == 1 ? marked.getFirst() + " 외" : OTHERS;
    }
}
