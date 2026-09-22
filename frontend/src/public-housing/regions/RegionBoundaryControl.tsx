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
      <strong>검색 지역: {name}</strong>
      <div className={styles.actions}>
        <button type="button" onClick={onRecenter} disabled={!canRecenter}>지역 다시 보기</button>
        <button type="button" onClick={onClear}>표시 해제</button>
      </div>
      {!supported && <p role="status">이 지역은 경계 정보를 제공하지 않습니다.</p>}
      {supported && status === 'loading' && <p role="status">지역 경계를 불러오는 중입니다.</p>}
      {supported && status === 'error' && (
        <div role="alert">
          <p>지역 경계를 불러오지 못했습니다.</p>
          <button type="button" onClick={onRetry}>경계 다시 시도</button>
        </div>
      )}
      {supported && (
        <small>
          경계 출처: <a href="https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?dsId=21" target="_blank" rel="noreferrer">국토교통부 · VWorld</a>
          {' · '}<a href="https://creativecommons.org/licenses/by/2.0/kr/" target="_blank" rel="noreferrer">CC BY 2.0 KR</a>
        </small>
      )}
    </section>
  )
}
