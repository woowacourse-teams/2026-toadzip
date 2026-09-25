import { type ReactNode } from 'react'
import styles from './DetailPrimitives.module.css'

interface DetailSectionProps {
  readonly title: string
  readonly description?: string
  readonly aside?: string
  readonly children: ReactNode
}

export function DetailSection({ title, description, aside, children }: DetailSectionProps) {
  return (
    <section className={styles.section}>
      <header className={styles.sectionHeading}>
        <div>
          <h3>{title}</h3>
          {description && <p>{description}</p>}
        </div>
        {aside && <span>{aside}</span>}
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
}

/** 항목명과 값의 관계는 dl로 유지하고 표 형태로 정렬한다. */
export function DetailFacts({ children, columns = 2 }: DetailFactsProps) {
  return <dl className={styles.facts} data-columns={columns}>{children}</dl>
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
      <dd>{value}</dd>
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
