package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 공고문이 정본인 공고(PDF로 보정한 공고)의 공급표를 쓴다. 옛 대상·수 테이블 쓰기에 붙은 DB 트리거는 이 공고를 건너뛰므로,
 * 이 공고의 옛 테이블에 쓰는 애플리케이션 경로는 모두 같은 트랜잭션에서 flush한 뒤 부른다. 빠뜨리면 공급표가 조용히 낡는다.
 *
 * <p>지금 PDF 보정 요청은 옛 대상·수 모양이라 옛 테이블에 저장한 값을 V20261113_02의 투영 규칙 그대로 공급표에 옮긴다. 규칙을 두 곳에
 * 두지 않으려고 같은 DB 함수를 부른다. 공급대상은 (공고, 표기, 신청 조건)으로 다시 써서 ID가 유지된다.
 */
@Repository
@RequiredArgsConstructor
public class SupplySheetWriter {

    private final JdbcClient jdbc;

    public void writeFromDocument(Announcement announcement) {
        if (!announcement.isSupplySheetFromDocument()) {
            return;
        }
        jdbc.sql("SELECT supply_sheet_rebuild(ARRAY[?]::BIGINT[])").param(announcement.getId()).query().singleRow();
    }
}
