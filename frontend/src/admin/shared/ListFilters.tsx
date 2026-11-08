import { useId, useState, type ReactNode } from 'react'
import { IconButton } from '../../design-system/components/IconButton'
import styles from './ListFilters.module.css'

export type AppliedFilter = { key: string; label: string }

export function ListFilters({ keyword, searchLabel, onSearch, primary, more, applied, onRemove, onReset,
  initiallyExpanded = false }: {
  keyword: string; searchLabel: string; onSearch: (value: string) => void
  primary: ReactNode; more?: ReactNode; applied: AppliedFilter[]
  onRemove: (key: string) => void; onReset: () => void; initiallyExpanded?: boolean
}) {
  const [draft, setDraft] = useState(keyword)
  const [expanded, setExpanded] = useState(initiallyExpanded)
  const id = useId()
  return <div className={styles.filters}>
    <form data-admin-navigation className={styles.row} onSubmit={event => {
      event.preventDefault()
      onSearch(draft.trim())
    }}>
      <div className={styles.search}>
        <label className="sr-only" htmlFor={`${id}-search`}>{searchLabel}</label>
        <input id={`${id}-search`} name="keyword" value={draft} maxLength={200}
          placeholder={`${searchLabel} 검색`} onChange={event => setDraft(event.target.value)} />
        <IconButton type="submit" label="검색" title="검색" data-design-system="icon-button">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4 4"/></svg>
        </IconButton>
      </div>
      {primary}
      {more && <button data-design-system="list-filter" type="button" className={styles.toggle} aria-expanded={expanded}
        aria-controls={`${id}-more`} onClick={() => setExpanded(value => !value)}>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M4 7h16M4 17h16"/><circle cx="9" cy="7" r="2"/><circle cx="15" cy="17" r="2"/></svg>
        추가 필터
      </button>}
    </form>
    {more && <div id={`${id}-more`} className={styles.more} hidden={!expanded}>{more}</div>}
    {applied.length > 0 && <div className={styles.applied} aria-label="적용된 필터">
      {applied.map(filter => <button key={filter.key} type="button" aria-label={`${filter.label} 해제`}
        onClick={() => onRemove(filter.key)}>{filter.label}<span aria-hidden="true">×</span></button>)}
      <button data-design-system="list-filter" type="button" className={styles.reset} onClick={onReset}>초기화</button>
    </div>}
  </div>
}
