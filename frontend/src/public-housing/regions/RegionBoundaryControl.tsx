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
        <strong className={styles.name} title={name}>{name}</strong>
        <button
          className={styles.recenter}
          type="button"
          onClick={onRecenter}
          disabled={!canRecenter}
        >
          전체 보기
        </button>
        <button
          className={styles.clear}
          type="button"
          onClick={onClear}
        >
          경계 지우기
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
    </section>
  )
}
