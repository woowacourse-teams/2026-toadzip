SELECT
 multiIf(period.days = 1, 'DAU · 오늘', period.days = 7, 'WAU · 오늘 포함 7일', 'MAU · 오늘 포함 30일') AS metric,
 toDate(addDays(toStartOfDay(toTimeZone(now(), 'Asia/Seoul')), 1 - period.days)) AS period_start_kst,
 toDate(toTimeZone(now(), 'Asia/Seoul')) AS period_end_kst,
 if(data.collection_evidence = 0, NULL, multiIf(period.days = 1, data.dau, period.days = 7, data.wau, data.mau)) AS observed_active_browsers,
 if(data.collection_evidence = 0, '수집 확인 필요', '관측값 · 수집 시작 전과 누락은 포함하지 않음') AS measurement_status
FROM (
 SELECT
  uniqExactIf(distinct_id, event IN ('view_complex', 'view_announcement') AND timestamp >= toStartOfDay(toTimeZone(now(), 'Asia/Seoul'))) AS dau,
  uniqExactIf(distinct_id, event IN ('view_complex', 'view_announcement') AND timestamp >= addDays(toStartOfDay(toTimeZone(now(), 'Asia/Seoul')), -6)) AS wau,
  uniqExactIf(distinct_id, event IN ('view_complex', 'view_announcement')) AS mau,
  count() AS collection_evidence
 FROM events
 WHERE timestamp >= addDays(toStartOfDay(toTimeZone(now(), 'Asia/Seoul')), -29)
  AND timestamp < now()
  AND properties.environment = 'dev'
  AND event IN ('page_view', 'view_complex', 'view_announcement')
) AS data
CROSS JOIN (SELECT arrayJoin([1, 7, 30]) AS days) AS period
ORDER BY period.days
