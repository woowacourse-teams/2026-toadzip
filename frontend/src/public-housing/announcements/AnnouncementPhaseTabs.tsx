import { type KeyboardEvent, useRef } from 'react'
import styles from './AnnouncementPhaseTabs.module.css'

const TABS = [
  { closed: false, label: '모집 중' },
  { closed: true, label: '접수마감' },
] as const

interface AnnouncementPhaseTabsProps {
  /** 접수마감 탭을 보고 있는지 */
  readonly closed: boolean
  /** 탭이 바꾸는 공고 목록 영역의 id */
  readonly panelId: string
  readonly onChange: (closed: boolean) => void
}

/** 공고 목록을 모집 중인 공고와 이후 일정이 남은 접수마감 공고로 나눈다. */
export function AnnouncementPhaseTabs({ closed, panelId, onChange }: AnnouncementPhaseTabsProps) {
  const tabs = useRef<(HTMLButtonElement | null)[]>([])

  function select(nextClosed: boolean) {
    if (nextClosed !== closed) onChange(nextClosed)
  }

  function move(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    const last = TABS.length - 1
    const next = event.key === 'ArrowRight' ? (index === last ? 0 : index + 1)
      : event.key === 'ArrowLeft' ? (index === 0 ? last : index - 1)
        : event.key === 'Home' ? 0 : event.key === 'End' ? last : null
    if (next === null) return
    event.preventDefault()
    select(TABS[next].closed)
    tabs.current[next]?.focus()
  }

  return (
    <div className={styles.tabs} role="tablist" aria-label="공고 진행 상태">
      {TABS.map((tab, index) => {
        const selected = tab.closed === closed
        return (
          <button
            key={tab.label}
            ref={(element) => { tabs.current[index] = element }}
            id={`${panelId}-tab-${tab.closed ? 'closed' : 'open'}`}
            className={styles.tab}
            type="button"
            role="tab"
            aria-selected={selected}
            aria-controls={selected ? panelId : undefined}
            tabIndex={selected ? 0 : -1}
            onClick={() => select(tab.closed)}
            onKeyDown={(event) => move(event, index)}
          >
            {tab.label}
          </button>
        )
      })}
    </div>
  )
}
