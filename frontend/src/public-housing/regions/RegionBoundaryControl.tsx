import styles from './RegionBoundaryControl.module.css'

interface Props {
  readonly presentation?: 'desktop' | 'mobile-header' | 'mobile-collapsed'
  readonly name: string
  readonly status: 'idle' | 'loading' | 'ready' | 'error'
  readonly supported: boolean
  readonly canRecenter: boolean
  readonly listExpanded: boolean
  readonly onOpenList: () => void
  readonly onRecenter: () => void
  readonly onClear: () => void
  readonly onRetry: () => void
}

export function RegionBoundaryControl(props: Props) {
  if (props.presentation && props.presentation !== 'desktop') return <MobileRegionBoundaryControl {...props} />
  const { name, status, supported, canRecenter, listExpanded, onOpenList, onRecenter, onClear, onRetry } = props
  return (
    <section className={styles.control} aria-label="검색 지역 표시">
      <div className={styles.row}>
        <button className={styles.name} title={name} type="button" id="region-complex-list-trigger"
          aria-label={`${name} 단지 목록 보기`} aria-controls="complex-results-panel"
          aria-expanded={listExpanded} onClick={onOpenList}>{name}</button>
        <button
          className={styles.recenter}
          type="button"
          onClick={onRecenter}
          disabled={!canRecenter}
        >
          해당 지역으로 이동
        </button>
        <button
          className={styles.clear}
          type="button"
          onClick={onClear}
        >
          경계 지우기
        </button>
      </div>
      {!supported && <p className={styles.message} role="status">이 지역은 경계 정보를 제공하지 않아요.</p>}
      {supported && status === 'loading' && <p className={styles.message} role="status">지역 경계를 불러오는 중이에요.</p>}
      {supported && status === 'error' && (
        <div className={styles.feedback} role="alert">
          <p className={styles.message}>지역 경계를 불러오지 못했어요.</p>
          <button className={styles.retry} type="button" onClick={onRetry}>경계 다시 시도</button>
        </div>
      )}
    </section>
  )
}

function MobileRegionBoundaryControl({ name, status, supported, canRecenter, listExpanded, onOpenList, onRecenter, onClear, onRetry, presentation }: Props) {
  return <div className={presentation === 'mobile-collapsed' ? styles.collapsed : styles.headerAction}>
    <div className={styles.mobileRow}>
      {presentation === 'mobile-collapsed' && <button type="button" className={styles.reopen}
        id="region-complex-list-trigger" title={name} aria-label={`${name} 단지 목록 보기`}
        aria-expanded={listExpanded} aria-controls="complex-results-panel" onClick={onOpenList}>
        <strong>{name}</strong><span>단지 목록 보기 ↑</span>
      </button>}
      <div className={styles.mobileActions}>
        <button type="button" disabled={!canRecenter} onClick={onRecenter}>선택 지역으로 이동</button>
        <button type="button" onClick={onClear}>선택 해제</button>
      </div>
    </div>
    {!supported && <p className={styles.mobileMessage} role="status">경계 정보 미제공</p>}
    {supported && status === 'loading' && <p className={styles.mobileMessage} role="status">경계를 불러오는 중</p>}
    {supported && status === 'error' && <div className={styles.mobileFeedback} role="alert">
      <span>경계 로딩 실패</span><button type="button" onClick={onRetry}>경계 다시 시도</button>
    </div>}
  </div>
}
