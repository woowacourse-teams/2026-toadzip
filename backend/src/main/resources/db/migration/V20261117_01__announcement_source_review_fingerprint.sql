-- 마지막 정제가 본 기관 원천(기본 정보·LH 일정) 지문과, 관리자가 원천 변경 확인을 마칠 때 본 지문.
-- 원천이 확인한 지문과 같으면 이미 확인한 차이라 원천 검토 대상으로 다시 표시하지 않는다. 기존 공고는 다음 정제가 채운다.
ALTER TABLE announcements
    ADD COLUMN source_fingerprint VARCHAR(64),
    ADD COLUMN source_review_acknowledged_fingerprint VARCHAR(64);
