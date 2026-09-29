import { useId, useRef, useState } from 'react'
import type { FocusEvent, KeyboardEvent } from 'react'
import styles from './DocumentOutline.module.css'

export interface DocumentOutlineEntry {
  readonly id: string
  readonly title: string
  readonly depth: number
  readonly pageNumber: number
}

interface DocumentOutlineProps {
  readonly entries: readonly DocumentOutlineEntry[]
  readonly activeId: string | null
  readonly onNavigate: (id: string) => void
}

export default function DocumentOutline({ entries, activeId, onNavigate }: DocumentOutlineProps) {
  const navigationId = useId()
  const toggleRef = useRef<HTMLButtonElement>(null)
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)
  const [mode, setMode] = useState<'auto' | 'pinned' | 'dismissed'>('auto')
  const open = mode === 'pinned' || (mode === 'auto' && (hovered || focused))
  const railStride = Math.ceil(entries.length / 18)
  const activeIndex = entries.findIndex((entry) => entry.id === activeId)
  const railEntries = entries.filter((_, index) =>
    index === 0 || index === entries.length - 1 || index === activeIndex || index % railStride === 0,
  )

  if (entries.length === 0) return null

  function handleBlur(event: FocusEvent<HTMLElement>) {
    if (event.currentTarget.contains(event.relatedTarget)) return
    setFocused(false)
    setMode((current) => current === 'dismissed' && !hovered ? 'auto' : current)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key !== 'Escape' || !open) return
    event.preventDefault()
    event.stopPropagation()
    setMode('dismissed')
    toggleRef.current?.focus()
  }

  function handleNavigate(id: string) {
    setMode('dismissed')
    toggleRef.current?.focus()
    onNavigate(id)
  }

  return (
    <aside
      className={styles.outline}
      data-document-outline
      data-outline-open={open}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => {
        setHovered(false)
        setMode((current) => current === 'dismissed' && !focused ? 'auto' : current)
      }}
      onFocus={() => setFocused(true)}
      onBlur={handleBlur}
      onKeyDown={handleKeyDown}
    >
      <button
        ref={toggleRef}
        type="button"
        className={styles.toggle}
        aria-label="목차"
        aria-expanded={open}
        aria-controls={navigationId}
        onClick={() => setMode((current) => current === 'pinned' ? 'dismissed' : 'pinned')}
      >
        <span className={styles.rail} aria-hidden="true">
          {railEntries.map((entry) => (
            <span
              key={entry.id}
              className={`${styles.mark} ${entry.id === activeId ? styles.activeMark : ''}`}
            />
          ))}
        </span>
        <span className={styles.mobileLabel}>목차</span>
      </button>
      <nav id={navigationId} className={styles.panel} aria-label="문서 목차" hidden={!open}>
        {open && (
          <>
            <div className={styles.heading}>목차</div>
            <ol className={styles.list}>
              {entries.map((entry) => (
                <li key={entry.id}>
                  <button
                    type="button"
                    className={styles.item}
                    data-depth={entry.depth}
                    aria-current={entry.id === activeId ? 'location' : undefined}
                    style={{ paddingInlineStart: `${12 + Math.min(Math.max(entry.depth, 0), 4) * 12}px` }}
                    onClick={() => handleNavigate(entry.id)}
                  >
                    <span className={styles.title}>{entry.title}</span>
                    <span className={styles.page}>{entry.pageNumber}쪽</span>
                  </button>
                </li>
              ))}
            </ol>
          </>
        )}
      </nav>
    </aside>
  )
}
