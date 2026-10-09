import { useRef } from 'react'
import { useNotificationInterests } from './NotificationInterestContext'
import type { NotificationTargetType } from './notificationInterestRepository'
import styles from './NotificationSettings.module.css'

const groups: readonly { type: NotificationTargetType; title: string }[] = [
  { type: 'COMPLEX', title: '단지' }, { type: 'ANNOUNCEMENT', title: '공고' }, { type: 'REGION', title: '지역' },
]

export function NotificationSettings() {
  const context = useNotificationInterests()
  const heading = useRef<HTMLHeadingElement>(null)
  if (!context || context.mode === 'loading') return <p role="status">알림 설정을 불러오는 중…</p>
  if (context.mode !== 'member') return null
  return <div className={styles.settings}>
    <div className={styles.actions}>
      <h3 ref={heading} tabIndex={-1}>등록한 알림 <span>{context.targets.length}</span></h3>
      <button type="button" disabled={context.blocked || context.targets.length === 0}
        onClick={(event) => { heading.current?.focus(); context.clearAll(event.currentTarget) }}>
        {context.clearingAll ? '전체 해제 중…' : '전체 해제'}
      </button>
    </div>
    {context.batchError && <p className={styles.error} role="alert">{context.batchError}</p>}
    {context.targets.length === 0 ? <p className={styles.empty}>등록한 알림이 없어요.</p> : groups.map(({ type, title }) => {
      const targets = context.targets.filter((target) => target.type === type)
      if (targets.length === 0) return null
      return <section className={styles.group} key={type} aria-label={`${title} 알림 설정`}>
        <h4>{title}</h4>
        <ul>{targets.map((target) => <li key={target.id}>
          <span>{target.name}</span>
          <button type="button" disabled={context.blocked} aria-label={`${target.name} 알림 해제`}
            onClick={(event) => { heading.current?.focus(); context.request({ target, source: 'SETTING' }, event.currentTarget) }}>해제</button>
        </li>)}</ul>
      </section>
    })}
  </div>
}
