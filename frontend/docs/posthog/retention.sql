WITH daily_activity AS (
 SELECT toDate(toTimeZone(timestamp, 'Asia/Seoul')) AS activity_date, distinct_id
 FROM events
 WHERE event IN ('view_complex', 'view_announcement') AND properties.environment = 'dev'
  AND timestamp >= addDays(toStartOfDay(toTimeZone(now(), 'Asia/Seoul')), -29) AND timestamp < now()
 GROUP BY activity_date, distinct_id
), browser_returns AS (
 SELECT cohort.activity_date AS cohort_date, cohort.distinct_id AS browser_id,
  max(if(dateDiff('day', cohort.activity_date, returned.activity_date) BETWEEN 1 AND 7, 1, 0)) AS did_return
 FROM daily_activity AS cohort
 LEFT JOIN daily_activity AS returned ON cohort.distinct_id = returned.distinct_id
 GROUP BY cohort.activity_date, cohort.distinct_id
)
SELECT cohort_date, count() AS active_browsers,
 if(toDate(toTimeZone(now(), 'Asia/Seoul')) < addDays(cohort_date, 8), NULL, sum(did_return)) AS returned_browsers,
 if(toDate(toTimeZone(now(), 'Asia/Seoul')) < addDays(cohort_date, 8), NULL, round(100.0 * sum(did_return) / nullIf(count(), 0), 2)) AS retention_percent,
 if(toDate(toTimeZone(now(), 'Asia/Seoul')) < addDays(cohort_date, 8), '관찰 중', '관찰 완료 · 수집된 데이터 기준') AS measurement_status,
 addDays(cohort_date, 8) AS mature_on_kst
FROM browser_returns GROUP BY cohort_date ORDER BY cohort_date DESC
