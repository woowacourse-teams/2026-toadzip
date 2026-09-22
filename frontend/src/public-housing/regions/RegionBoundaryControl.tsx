import styles from './RegionBoundaryControl.module.css'

interface Props {
  readonly name: string
  readonly status: 'idle' | 'loading' | 'ready' | 'error'
  readonly supported: boolean
  readonly canRecenter: boolean
  readonly onRecenter: () => void
  readonly onClear: () => void
  readonly onRetry: () => void
}

export function RegionBoundaryControl({ name, status, supported, canRecenter, onRecenter, onClear, onRetry }: Props) {
  return (
    <section className={styles.control} aria-label="검색 지역 표시">
      <div className={styles.row}>
        <strong className={styles.name} title={`검색 지역: ${name}`}>검색 지역: {name}</strong>
        <button
          className={styles.recenter}
          type="button"
          aria-label="지역 다시 보기"
          onClick={onRecenter}
          disabled={!canRecenter}
        >
          다시 보기
        </button>
        <button
          className={styles.clear}
          type="button"
          aria-label="표시 해제"
          title="표시 해제"
          onClick={onClear}
        >
          <span aria-hidden="true">×</span>
        </button>
      </div>
      {!supported && <p className={styles.message} role="status">이 지역은 경계 정보를 제공하지 않습니다.</p>}
      {supported && status === 'loading' && <p className={styles.message} role="status">지역 경계를 불러오는 중입니다.</p>}
      {supported && status === 'error' && (
        <div className={styles.feedback} role="alert">
          <p className={styles.message}>지역 경계를 불러오지 못했습니다.</p>
          <button className={styles.retry} type="button" onClick={onRetry}>경계 다시 시도</button>
        </div>
      )}
      {supported && (
        <small className={styles.source} title="지도 표시에 맞게 단순화한 경계입니다.">
          가공 경계: <a href="https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?dsId=21" target="_blank" rel="noreferrer">국토교통부 · VWorld</a>
          {' · '}<a href="https://creativecommons.org/licenses/by/2.0/kr/" target="_blank" rel="noreferrer">CC BY 2.0 KR</a>
        </small>
      )}
    </section>
  )
}
