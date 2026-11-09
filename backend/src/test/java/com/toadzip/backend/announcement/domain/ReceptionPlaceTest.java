package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 같은 곳 판정 규칙표. PDF 보정 화면(receptionPlaces.ts samePlace)과 같은 사례를 같은 결과로 판정해야 한다. */
class ReceptionPlaceTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {
            "이름 달라도 도로명·건물번호 같음 | VISIT | 관리사무소 | 경기도 평택시 팽성읍 팽성송화로 163(주공아파트) 관리사무소 | | VISIT | 평택송화단지 아파트 관리사무소 | 경기도 평택시 팽성읍 팽성송화로 163 | | true",
            "건물번호 다름 | VISIT | 관리사무소 | 경기도 평택시 팽성읍 팽성송화로 163 | | VISIT | 관리사무소 | 경기도 평택시 팽성읍 팽성송화로 165 | | false",
            "번길은 도로명의 일부 | VISIT | 지역본부 | 성남시 분당구 성남대로 54번길 3 | | VISIT | 대강당 | 경기도 성남시 분당구 성남대로54번길 3 1층 | | true",
            "번길 같고 건물번호 다름 | VISIT | 지역본부 | 성남대로 54번길 3 | | VISIT | 지역본부 | 성남대로 54번길 5 | | false",
            "온라인은 URL 호스트 | ONLINE | LH청약플러스 | | https://apply.lh.or.kr | ONLINE | 인터넷 및 모바일 신청 (LH청약플러스) | | apply.lh.or.kr/ | true",
            "방식 다름 | ONLINE | LH청약플러스 | | https://apply.lh.or.kr | VISIT | LH청약플러스 | | https://apply.lh.or.kr | false",
            "주소·URL 없으면 이름 | MAIL | 우편 접수 | | | MAIL | 우편접수 | | | true",
            "주소 없는 LH 접수처는 이름이 같아야 함 | VISIT | LH 접수처 | | | VISIT | LH 현장 접수처 | | | false",
    }, delimiter = '|')
    void 같은_곳인지는_방식과_도로명_건물번호_URL_호스트_이름_순으로_가린다(String label, String leftMethod, String leftName,
            String leftAddress, String leftUrl, String rightMethod, String rightName, String rightAddress, String rightUrl,
            boolean same) {
        var left = ReceptionPlace.create(leftName, ReceptionMethod.valueOf(leftMethod), leftAddress, null, leftUrl);
        var right = ReceptionPlace.create(rightName, ReceptionMethod.valueOf(rightMethod), rightAddress, null, rightUrl);

        assertThat(left.isSamePlace(right)).as(label).isEqualTo(same);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "031-651-6776 | 0316516776 | true",
            "031-651-6776 | 031-651-6777 | false",
            "031-651-6776 | | true",
    }, delimiter = '|')
    void LH_원천_접수처가_목록에_그대로_있으려면_같은_곳이고_연락처가_둘_다_있으면_숫자가_같아야_한다(String stored, String source,
            boolean same) {
        var kept = ReceptionPlace.create("평택송화단지 아파트 관리사무소", ReceptionMethod.VISIT, "경기도 평택시 팽성읍 팽성송화로 163",
                stored, null);
        var lh = ReceptionPlace.create("관리사무소", ReceptionMethod.VISIT, "경기도 평택시 팽성읍 팽성송화로 163(주공아파트) 관리사무소",
                source, null);

        assertThat(kept.isSameSourcePlace(lh)).isEqualTo(same);
    }
}
