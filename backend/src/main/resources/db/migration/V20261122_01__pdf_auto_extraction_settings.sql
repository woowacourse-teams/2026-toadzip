-- 관리자가 운영 중에 바꾼 PDF 자동 추출 켜짐 여부. 행이 없으면 PDF_AUTO_EXTRACTION_ENABLED 기본값을 쓴다.
CREATE TABLE pdf_auto_extraction_settings (
    id smallint PRIMARY KEY CHECK (id = 1),
    enabled boolean NOT NULL,
    updated_by varchar(255) NOT NULL,
    updated_at timestamptz NOT NULL
);
