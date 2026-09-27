import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { IntegratedSearch } from '../search/IntegratedSearch'
import type { IntegratedSearchRepository, SearchResultItem } from '../search/integratedSearchRepository'
import { NotificationInterestButton } from './NotificationInterest'
import type { NotificationTarget } from './notificationInterestRepository'
import styles from './NotificationSettings.module.css'

export function NotificationSettings({ searchRepository }: { readonly searchRepository?: IntegratedSearchRepository }) {
  const [open, setOpen] = useState(false)
  const [target, setTarget] = useState<NotificationTarget | null>(null)
  const panel = useRef<HTMLElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const wasOpen = useRef(false)
  const titleId = 'notification-settings-title'

  useEffect(() => {
    if (open) {
      wasOpen.current = true
      panel.current?.querySelector<HTMLInputElement>('input')?.focus()
    } else if (wasOpen.current) {
      wasOpen.current = false
      trigger.current?.focus()
    }
  }, [open])

  function select(item: SearchResultItem) {
    if (item.type === 'ANNOUNCEMENT') return
    setTarget({ type: item.type, id: item.regionCode ?? item.id, name: item.title })
  }

  return (
    <>
      <button ref={trigger} type="button" className={styles.trigger} aria-expanded={open}
        aria-controls="notification-settings" onClick={() => setOpen((current) => !current)}>알림 설정</button>
      {open && createPortal(
        <section ref={panel} id="notification-settings" className={styles.panel} aria-labelledby={titleId}
          onKeyDown={(event) => {
            if (event.key === 'Escape') { event.stopPropagation(); setOpen(false) }
          }}>
          <header className={styles.header}>
            <h2 id={titleId}>알림 설정</h2>
            <button type="button" aria-label="알림 설정 닫기" onClick={() => setOpen(false)}>×</button>
          </header>
          <p>알림을 받고 싶은 지역이나 단지를 선택해 주세요.</p>
          <p className={styles.notice}>알림 기능은 준비 중이며 현재 실제 알림은 발송되지 않습니다.</p>
          {target && (
            <div className={styles.selection}>
              <strong>{target.name}</strong>
              <NotificationInterestButton target={target} source="SETTING" />
            </div>
          )}
          <IntegratedSearch repository={searchRepository} onSelect={select} purpose="notification" />
        </section>, document.body,
      )}
    </>
  )
}
