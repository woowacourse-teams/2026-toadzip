package com.toadzip.backend.announcement.repository;

import java.util.List;

/**
 * 공고가 공고문 PDF 처리 흐름의 어디에 있는지 저장하지 않고 계산한다. 운영 홈 집계와 관리 목록 필터가 이 식 하나를 쓴다.
 *
 * <p>위에서부터 처음 맞는 단계다. 공급표를 확정한 공고는 확정(원천 공급이 바뀌었으면 재확인 필요)이고 추출 기록을 보지 않는다.
 * 확정 전 공고는 마지막 추출 작업의 상태로 정한다(진행 중인 작업은 공고마다 하나뿐이라 진행 중이면 마지막 작업이다). 추출한 적이
 * 없으면 PDF 보정 화면이 고를 수 있는 PDF 첨부가 있는지로 추출 전과 PDF 없음을 나눈다.
 */
public final class AnnouncementProcessingStageSql {
    public static final String PDF_MISSING = "PDF_MISSING";
    public static final String NOT_EXTRACTED = "NOT_EXTRACTED";
    public static final String EXTRACTING = "EXTRACTING";
    public static final String EXTRACTION_FAILED = "EXTRACTION_FAILED";
    public static final String REVIEW_PENDING = "REVIEW_PENDING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String RECHECK_REQUIRED = "RECHECK_REQUIRED";
    public static final List<String> STAGES = List.of(PDF_MISSING, NOT_EXTRACTED, EXTRACTING,
            EXTRACTION_FAILED, REVIEW_PENDING, CONFIRMED, RECHECK_REQUIRED);

    private AnnouncementProcessingStageSql() { }

    /**
     * alias 공고 행에 붙일 조인이다. 공고마다 상관 하위 조회를 돌리지 않도록 한 번씩 모아 해시 조인한다. PDF 첨부는 PDF 보정 화면과
     * 같이 이름이 .pdf로 끝나거나 뒤에 공백·물음표가 오는 첨부다. 네이티브 쿼리 매개변수로 읽히지 않게 물음표는 \x3f로 쓴다.
     */
    public static String joins(String alias) {
        return """
                LEFT JOIN (SELECT DISTINCT ON (announcement_id) announcement_id, status FROM ai_jobs
                    WHERE kind = 'ANNOUNCEMENT_PDF_EXTRACTION' ORDER BY announcement_id, id DESC) stage_job
                  ON stage_job.announcement_id = %1$s.id
                LEFT JOIN (SELECT DISTINCT announcement_id FROM announcement_attachments
                    WHERE file_name ~* '\\.pdf($|[\\s\\x3f])') stage_pdf
                  ON stage_pdf.announcement_id = %1$s.id
                """.formatted(alias);
    }

    /** {@link #joins}를 붙인 alias 공고 행의 단계 코드다. */
    public static String stage(String alias) {
        return """
                (CASE WHEN %1$s.supply_confirmed_at IS NOT NULL AND %1$s.supply_recheck_required THEN 'RECHECK_REQUIRED'
                  WHEN %1$s.supply_confirmed_at IS NOT NULL THEN 'CONFIRMED'
                  WHEN stage_job.status IN ('PENDING', 'RETRY', 'RUNNING') THEN 'EXTRACTING'
                  WHEN stage_job.status = 'SUCCEEDED' THEN 'REVIEW_PENDING'
                  WHEN stage_job.status = 'FAILED' THEN 'EXTRACTION_FAILED'
                  WHEN stage_pdf.announcement_id IS NOT NULL THEN 'NOT_EXTRACTED'
                  ELSE 'PDF_MISSING' END)
                """.formatted(alias);
    }
}
