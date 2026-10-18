import { type ReactNode } from 'react'
import styles from './DetailPrimitives.module.css'
import { MISSING_DATA_LABEL } from '../presentation/missingData'

interface DetailSectionProps {
  readonly title: string
  readonly description?: string
  readonly aside?: string
  readonly actions?: ReactNode
  readonly children: ReactNode
  /** band: 전체 폭 구분 띠, block: 회색 바탕 위 흰 섹션 블록 */
  readonly appearance?: 'band' | 'block'
}

export function DetailSection({ title, description, aside, actions, children, appearance = 'band' }: DetailSectionProps) {
  return (
    <section className={styles.section} data-appearance={appearance}>
      <header className={styles.sectionHeading} data-has-actions={actions ? true : undefined}>
        <div>
          <h3>{title}</h3>
          {description && <p>{description}</p>}
        </div>
        {aside && <span>{aside}</span>}
        {actions && <div className={styles.sectionActions}>{actions}</div>}
      </header>
      {children}
    </section>
  )
}

interface DetailFactProps {
  readonly term: string
  readonly value: string
  readonly wide?: boolean
  readonly emphasis?: boolean
}

interface DetailFactsProps {
  readonly children: ReactNode
  readonly columns?: 1 | 2
  /** table: 칸을 나눈 표 형태, list: 항목명 아래 값을 두는 이름/값 목록 */
  readonly appearance?: 'table' | 'list'
}

/** 항목명과 값의 관계는 dl로 유지한다. 행과 열을 비교하지 않는 나열은 list를 쓴다. */
export function DetailFacts({ children, columns = 2, appearance = 'table' }: DetailFactsProps) {
  return <dl className={styles.facts} data-columns={columns} data-appearance={appearance}>{children}</dl>
}

interface DetailTableProps {
  readonly caption: string
  readonly children: ReactNode
  readonly minWidth?: number
}

/** 일정·조건처럼 행과 열로 비교하는 데이터에 사용한다. */
export function DetailTable({ caption, children, minWidth }: DetailTableProps) {
  return (
    <div
      className={styles.tableViewport}
      data-scrollable={minWidth !== undefined || undefined}
      role="region"
      aria-label={`${caption} 표`}
      tabIndex={0}
    >
      <table className={styles.table} style={minWidth === undefined ? undefined : { minWidth }}>
        <caption>{caption}</caption>
        {children}
      </table>
    </div>
  )
}

/** dl 안에서 사용한다. 값의 단위·빈 값·도메인 계산은 호출자가 결정한다. */
export function DetailFact({ term, value, wide = false, emphasis = false }: DetailFactProps) {
  return (
    <div className={styles.fact} data-wide={wide || undefined} data-emphasis={emphasis || undefined}>
      <dt>{term}</dt>
      {/* 목록에서 빈 값은 이름만 떠 보이므로 흐린 결측 문구로 채운다. */}
      <dd>{value === '' ? <span className={styles.missingValue}>{MISSING_DATA_LABEL}</span> : value}</dd>
    </div>
  )
}

interface DetailCloseButtonProps {
  readonly label: string
  readonly onClose: () => void
}

export function DetailCloseButton({ label, onClose }: DetailCloseButtonProps) {
  return (
    <button className={styles.closeButton} type="button" aria-label={label} onClick={onClose}>
      <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
        <path d="m6 6 12 12M18 6 6 18" />
      </svg>
    </button>
  )
}
