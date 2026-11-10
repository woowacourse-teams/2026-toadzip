-- 발송 선점은 처리할 수 있는 작업 중 COALESCE(lease_until,next_attempt_at), id가 가장 앞선 1건을 고른다.
-- 기존 부분 인덱스(due, lease)로는 이 순서로 읽을 수 없어 대기 작업 전체를 조인·정렬했다(대기 건수에 비례).
-- 같은 정렬 키의 부분 인덱스로 맨 앞 1건만 읽게 한다. SENDING이 아니면 lease_until이 NULL인 CHECK 제약 덕분에
-- 정렬 키가 PENDING·RETRY는 next_attempt_at, SENDING은 lease_until과 같다.
CREATE INDEX idx_notification_deliveries_claim
    ON notification_deliveries ((COALESCE(lease_until, next_attempt_at)), id)
    WHERE status IN ('PENDING', 'RETRY', 'SENDING');
