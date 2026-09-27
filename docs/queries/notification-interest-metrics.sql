-- 최근 90일의 진입점·대상별 세션 클릭률. 노출을 확인한 세션만 분모와 분자에 포함한다.
WITH sessions AS (
    SELECT source, target_type, target_id, session_id,
           BOOL_OR(event_type = 'EXPOSED') AS exposed,
           BOOL_OR(event_type = 'CLICKED') AS clicked,
           COUNT(*) FILTER (WHERE event_type = 'CLICKED') AS clicks
    FROM notification_interest_events
    WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '90 days'
    GROUP BY source, target_type, target_id, session_id
)
SELECT source, target_type, target_id,
       COUNT(*) FILTER (WHERE exposed) AS exposed_sessions,
       COUNT(*) FILTER (WHERE exposed AND clicked) AS clicked_exposed_sessions,
       ROUND(100.0 * COUNT(*) FILTER (WHERE exposed AND clicked)
             / NULLIF(COUNT(*) FILTER (WHERE exposed), 0), 2) AS click_rate_percent,
       SUM(clicks) AS total_clicks,
       COUNT(*) FILTER (WHERE clicked) AS interested_sessions
FROM sessions
GROUP BY source, target_type, target_id
ORDER BY interested_sessions DESC, source, target_type, target_id;

-- 최초 질문을 마친 응답 중 긍정 응답 비율. 이후 클릭과 섞어 전환율로 해석하지 않는다.
SELECT COUNT(*) FILTER (WHERE event_type = 'CONFIRMED') AS confirmed,
       COUNT(*) FILTER (WHERE event_type = 'DECLINED') AS declined,
       ROUND(100.0 * COUNT(*) FILTER (WHERE event_type = 'CONFIRMED')
             / NULLIF(COUNT(*) FILTER (WHERE event_type IN ('CONFIRMED', 'DECLINED')), 0), 2) AS confirmation_rate_percent
FROM notification_interest_events
WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '90 days';
