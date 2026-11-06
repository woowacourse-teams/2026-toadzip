import styles from './OperationsHome.module.css'

export function OperationsTrend({ points, label, secondaryLabel }: { points: { day: string; count: number | null; secondary?: number | null }[]; label: string; secondaryLabel?: string }) {
  const max = Math.max(1, ...points.flatMap(point => [point.count ?? 0, point.secondary ?? 0]))
  if (!points.length) return <div className={styles.chartEmpty}>미집계</div>
  const date = (value: string) => value.slice(5).replace('-', '.')
  return <div className={styles.chart}>
    <div className={styles.chartLegend}><span><i />{label}</span>{secondaryLabel ? <span><i className={styles.secondary} />{secondaryLabel}</span> : null}</div>
    <div className={styles.chartBars} role="img" aria-label={`${label}${secondaryLabel ? `·${secondaryLabel}` : ''} 일별 추이`}>
      {points.map(point => <span key={point.day} title={`${date(point.day)} · ${label} ${point.count ?? '미집계'}${secondaryLabel ? ` · ${secondaryLabel} ${point.secondary ?? '미집계'}` : ''}`}>
        <i data-missing={point.count === null} style={{ height: point.count === null ? '100%' : `${point.count / max * 100}%` }} />
        {secondaryLabel ? <i className={styles.secondary} data-missing={point.secondary == null} style={{ height: point.secondary == null ? '100%' : `${point.secondary / max * 100}%` }} /> : null}
      </span>)}
    </div>
    <div className={styles.chartAxis}><time>{date(points[0].day)}</time><time>{date(points[Math.floor(points.length / 2)].day)}</time><time>{date(points[points.length - 1].day)}</time></div>
    <table className={styles.srOnly}><caption>{label} 일별 집계</caption><thead><tr><th>날짜</th><th>{label}</th>{secondaryLabel ? <th>{secondaryLabel}</th> : null}</tr></thead><tbody>{points.map(point => <tr key={point.day}><th>{point.day}</th><td>{point.count ?? '미집계'}</td>{secondaryLabel ? <td>{point.secondary ?? '미집계'}</td> : null}</tr>)}</tbody></table>
  </div>
}
