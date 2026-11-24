import { useId, useState, type ReactNode } from 'react'
import { IconButton } from '../../design-system/components/IconButton'
import styles from './ListFilters.module.css'
import { AdminIcon } from './AdminIcon'

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
          <AdminIcon name="search" size={18} />
        </IconButton>
      </div>
      {primary}
      {more && <button data-design-system="list-filter" type="button" className={styles.toggle} aria-expanded={expanded}
        aria-controls={`${id}-more`} onClick={() => setExpanded(value => !value)}>
        <AdminIcon name="filter" size={16} />
        추가 필터
      </button>}
    </form>
    {more && <div id={`${id}-more`} className={styles.more} hidden={!expanded}>{more}</div>}
    {applied.length > 0 && <div className={styles.applied} aria-label="적용된 필터">
      {applied.map(filter => <button key={filter.key} type="button" aria-label={`${filter.label} 해제`}
        onClick={() => onRemove(filter.key)}>{filter.label}<AdminIcon name="close" size={14} /></button>)}
      <button data-design-system="list-filter" type="button" className={styles.reset} onClick={onReset}>초기화</button>
    </div>}
  </div>
}
