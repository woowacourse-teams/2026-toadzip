import { type ReactNode, useId, useState } from 'react'
import type { ScheduleTarget } from '../presentation/announcementSchedulePresentation'
import styles from './ScheduleGroups.module.css'

interface Props {
  readonly name: string
  readonly targets: readonly ScheduleTarget[]
  readonly caption: string
  readonly children: ReactNode
}

export function ScheduleGroupCard({ name, targets, caption, children }: Props) {
  const id = useId()
  const [expanded, setExpanded] = useState(false)
  const shared = targets.length > 1
  const collapsible = targets.length > 3
  const title = shared ? '공통 일정' : name
  return (
    <section className={styles.group} aria-label={`${name} ${caption}`}>
      <header className={styles.groupHeader}>
        <div className={styles.groupHeading}>
          <h4>{title}</h4>
          {shared && <span className={styles.sharedBadge}>{targets.length}개 단지</span>}
        </div>
        {shared && <div className={styles.targets} role="group" aria-label="대상 단지" id={`${id}-targets`}>
          {targets.map((target, index) => (
            <span key={target.key} className={styles.target} hidden={collapsible && !expanded && index >= 2}>
              {target.name}
            </span>
          ))}
          {collapsible && <button type="button" className={styles.targetToggle}
            aria-expanded={expanded} aria-controls={`${id}-targets`} onClick={() => setExpanded(!expanded)}>
            {expanded ? '단지명 접기' : `외 ${targets.length - 2}개 단지`}
          </button>}
        </div>}
      </header>
      <ul className={styles.timeline} aria-label={caption}>{children}</ul>
    </section>
  )
}
