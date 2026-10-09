SELECT
 if(count() = 0, NULL, uniqExactIf(distinct_id, event = 'notification_preregistration_completed' AND properties.target_type = 'COMPLEX')) AS preregistered_browsers,
 if(count() = 0, '수집 확인 필요', '관측값 · 선택 기간에 특정 단지 알림 사전신청 완료') AS measurement_status
FROM events
WHERE event IN ('page_view', 'view_complex', 'view_announcement', 'notification_preregistration_completed')
 AND properties.environment = 'dev' AND timestamp < now() AND {filters}
