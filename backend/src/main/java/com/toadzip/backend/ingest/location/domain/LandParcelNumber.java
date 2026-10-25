package com.toadzip.backend.ingest.location.domain;

/**
 * 필지 고유번호(PNU, 19자리) = 법정동코드 10자리 + 산 구분 1자리(일반 1, 산 2) + 본번 4자리 + 부번 4자리.
 */
public final class LandParcelNumber {

    private LandParcelNumber() {
    }

    public static String of(String legalDongCode, boolean mountain, int mainNumber, int subNumber) {
        if (legalDongCode == null || !legalDongCode.matches("\\d{10}")) {
            throw new IllegalArgumentException("법정동코드는 10자리 숫자여야 합니다.");
        }
        if (mainNumber < 0 || mainNumber > 9999 || subNumber < 0 || subNumber > 9999) {
            throw new IllegalArgumentException("지번 본번·부번은 0~9999 사이여야 합니다.");
        }
        return legalDongCode + (mountain ? '2' : '1') + "%04d%04d".formatted(mainNumber, subNumber);
    }
}
