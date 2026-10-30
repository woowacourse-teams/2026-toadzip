import { useId, useRef, useState } from 'react'
import { IconButton } from '../../design-system/components/IconButton'
import styles from './ComplexIngest.module.css'

export function ComplexStageHelp({ stage, children }: { stage: string; children: string }) {
  const id = useId()
  const container = useRef<HTMLSpanElement>(null)
  const [open, setOpen] = useState(false)
  return <span ref={container} className={styles.stageHelp} onMouseEnter={() => setOpen(true)}
    onMouseLeave={() => { if (!container.current?.contains(document.activeElement)) setOpen(false) }}
    onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false) }}>
    <IconButton data-design-system label={`${stage} 안내`} className={styles.infoButton}
      aria-describedby={open ? id : undefined} onFocus={() => setOpen(true)} onClick={() => setOpen(true)}
      onKeyDown={event => { if (event.key === 'Escape') { event.stopPropagation(); setOpen(false) } }}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" aria-hidden="true">
        <circle cx="12" cy="12" r="9" /><path d="M12 11v6M12 7h.01" />
      </svg>
    </IconButton>
    {open && <span id={id} role="tooltip" className={styles.stageHelpText}>{children}</span>}
  </span>
}
