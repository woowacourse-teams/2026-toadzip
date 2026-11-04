package com.toadzip.backend.ingest.sh.refinement.dto;

import java.time.LocalDate;

/**
 * SH 게시글 본문 요약에서 읽은 공고 단위 후보값이다. 확실하지 않은 값은 null이며, 관리자가 PDF 보정에서 확인한다.
 * totalSupplyCount는 공고에 저장하지 않고 PDF 보정의 공급행 합계와 대조하는 참고값이다.
 */
public record ShAnnouncementBodyValues(
        LocalDate applicationStartDate, LocalDate applicationEndDate, LocalDate winnerAnnouncementDate,
        Integer totalSupplyCount
) {
}
