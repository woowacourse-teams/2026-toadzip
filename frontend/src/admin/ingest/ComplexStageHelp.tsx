import { useId, useRef, useState } from 'react'
import { IconButton } from '../../design-system/components/IconButton'
import styles from './ComplexIngest.module.css'
import { AdminIcon } from '../shared/AdminIcon'

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
      <AdminIcon name="info" size={16} />
    </IconButton>
    {open && <span id={id} role="tooltip" className={styles.stageHelpText}>{children}</span>}
  </span>
}
