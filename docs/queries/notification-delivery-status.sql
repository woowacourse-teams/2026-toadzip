-- 읽기 전용 운영 진단. 사용자 ID, FID, 기기 쿠키, 제목을 출력하지 않는다.
SELECT status, count(*) AS count,
       max(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - occurred_at))) AS oldest_age_seconds
FROM notification_outbox_events GROUP BY status ORDER BY status;

SELECT status, count(*) AS count,
       max(GREATEST(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - next_attempt_at)), 0)) AS max_due_delay_seconds
FROM notification_deliveries GROUP BY status ORDER BY status;

SELECT last_error_code, count(*) AS count
FROM notification_deliveries WHERE status = 'FAILED'
GROUP BY last_error_code ORDER BY count DESC;

SELECT count(*) AS expired_leases
FROM notification_deliveries WHERE status = 'SENDING' AND lease_until <= CURRENT_TIMESTAMP;

SELECT count(*) AS expired_inbox_waiting_for_references
FROM user_notifications n WHERE expires_at <= CURRENT_TIMESTAMP
  AND EXISTS (SELECT 1 FROM notification_deliveries d WHERE d.notification_id = n.id);
