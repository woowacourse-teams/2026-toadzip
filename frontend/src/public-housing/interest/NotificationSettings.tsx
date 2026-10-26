import { useId, useRef } from 'react'
import { Link, useSearchParams } from 'react-router'
import { useMyPageReturnState } from '../../user/mypage/mypageNavigation'
import { ManagementTable, ManagementRow } from '../../user/mypage/ManagementTable'
import { NotificationDeviceList } from '../../notifications/NotificationDeviceList'
import { NotificationPushControl } from '../../notifications/NotificationPushControl'
import { useNotificationInterests } from './NotificationInterestContext'
import type { NotificationTargetType } from './notificationInterestRepository'
import styles from './NotificationSettings.module.css'

const groups = [
  { type: 'COMPLEX', title: '단지' }, { type: 'ANNOUNCEMENT', title: '공고' }, { type: 'REGION', title: '지역' },
] as const satisfies readonly { type: NotificationTargetType; title: string }[]

export function NotificationSettings() {
  const context = useNotificationInterests()
  const returnState = useMyPageReturnState()
  const heading = useRef<HTMLHeadingElement>(null)
  const tabs = useRef<Array<HTMLButtonElement | null>>([])
  const id = useId()
  const [params, setParams] = useSearchParams()
  const selected = groups.find((group) => group.type === params.get('type')) ?? groups[0]

  function select(type: NotificationTargetType) {
    setParams((current) => {
      const next = new URLSearchParams(current)
      if (type === 'COMPLEX') next.delete('type')
      else next.set('type', type)
      return next
    }, { replace: true })
  }

  if (!context || context.mode === 'loading') return <p role="status">알림 설정을 불러오는 중…</p>
  if (context.mode !== 'member') return null
  const targets = context.targets.filter((target) => target.type === selected.type)

  return <div className={styles.settings}>
    <NotificationPushControl />
    <div className={styles.actions}>
      <h3 ref={heading} tabIndex={-1}>관심 알림 <span>{context.targets.length}</span></h3>
      <button className={styles.actionButton} type="button" disabled={context.blocked || context.targets.length === 0}
        onClick={(event) => { heading.current?.focus(); context.clearAll(event.currentTarget) }}>
        {context.clearingAll ? '전체 해제 중…' : '모든 알림 해제'}
      </button>
    </div>
    {context.batchError && <p className={styles.error} role="alert">{context.batchError}</p>}
    <div className={styles.tabs} role="tablist" aria-label="알림 대상 유형">
      {groups.map(({ type, title }, index) => <button key={type} ref={(element) => { tabs.current[index] = element }}
        id={`${id}-${type}`} type="button" role="tab" aria-selected={selected.type === type}
        aria-label={`${title} ${context.targets.filter((target) => target.type === type).length}`}
        aria-controls={`${id}-panel`} tabIndex={selected.type === type ? 0 : -1}
        onClick={() => select(type)} onKeyDown={(event) => {
          const next = event.key === 'ArrowRight' ? (index + 1) % groups.length
            : event.key === 'ArrowLeft' ? (index + groups.length - 1) % groups.length
            : event.key === 'Home' ? 0 : event.key === 'End' ? groups.length - 1 : null
          if (next === null) return
          event.preventDefault()
          select(groups[next]!.type)
          tabs.current[next]?.focus()
        }}>
        <span>{title}</span><span className={styles.count}>{context.targets.filter((target) => target.type === type).length}</span>
      </button>)}
    </div>
    <section id={`${id}-panel`} role="tabpanel" aria-labelledby={`${id}-${selected.type}`} tabIndex={0} className={styles.panel}>
      {targets.length === 0
        ? <p className={styles.empty}>{context.targets.length === 0 ? '등록한 알림이 없어요.' : `등록한 ${selected.title} 알림이 없어요.`}</p>
        : <ManagementTable label={`${selected.title} 알림 신청 내역`}>
          {targets.map((target) => <ManagementRow key={target.id} actions={
            <button className={styles.actionButton} type="button" disabled={context.blocked} aria-label={`${target.name} 알림 해제`}
              onClick={(event) => { heading.current?.focus(); context.request({ target, source: 'SETTING' }, event.currentTarget) }}>알림 해제</button>
          }><strong>{target.type === 'REGION' ? target.name : <Link
            to={`/explore?${target.type === 'COMPLEX' ? 'complexId' : 'announcementId'}=${encodeURIComponent(target.id)}`}
            state={returnState}>{target.name}</Link>}</strong></ManagementRow>)}
        </ManagementTable>}
    </section>
    <NotificationDeviceList />
  </div>
}
